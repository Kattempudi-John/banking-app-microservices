package com.example.transactionservice.controller;

import com.example.transactionservice.client.AccountServiceClient;
import com.example.transactionservice.model.TransactionEntity;
import com.example.transactionservice.model.TransactionStatus;
import com.example.transactionservice.repository.TransactionRepository;
import com.example.transactionservice.service.RecipientKycValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Settles wires that were held for fraud review, either releasing or refunding the money.
 *
 * <p>Carries no {@code @PreAuthorize}, unlike the customer-facing controllers: it is protected by
 * being unreachable from outside the cluster rather than by an authority check, so anything that
 * can reach the {@code /api/v1/internal/} prefix can resolve any held wire. Exposing that prefix
 * through the ingress would hand an outsider the ability to release or reverse other people's
 * money.
 *
 * <p>Nothing in this repository calls this endpoint. It is the only way a wire ever leaves
 * {@code PENDING_APPROVAL}, so until an external reviewer or operator drives it, every wire above
 * the review threshold stays held indefinitely with the sender already debited.
 */
@RestController
@RequestMapping("/api/v1/internal/transfers")
public class InternalFraudController {

    private final FraudResolutionService fraudResolutionService;

    public InternalFraudController(FraudResolutionService fraudResolutionService) {
        this.fraudResolutionService = fraudResolutionService;
    }

    /**
     * A reviewer's verdict on a held wire.
     *
     * @param status required, and only {@code APPROVED} or {@code REJECTED} are accepted; anything
     *     else is rejected as 400 before the transfer is touched
     * @param reviewerNotes optional free text, appended verbatim to the transaction description
     *     that the customer can read, so it must not carry anything internal
     */
    public record FraudReviewUpdateDto(
            @NotBlank(message = "Status is required")
            @Pattern(regexp = "^(APPROVED|REJECTED)$", message = "Status must be APPROVED or REJECTED")
            String status,

            String reviewerNotes
    ) {}

    /**
     * Resolves a held wire, releasing the funds to the destination or refunding the sender.
     *
     * <p>{@code APPROVED} completes the wire: for a genuinely external one the money already left at
     * initiation and only the record changes, while for an on-us one the destination is credited
     * now. The recipient's identity verification is re-checked at this moment rather than trusted
     * from initiation, because a held wire can sit for days and a verification can be revoked in the
     * meantime; a recipient who no longer qualifies gets the wire refunded instead, recorded under
     * its own reason so the audit trail does not file it as a fraud rejection the reviewer never
     * made. That re-check fails closed — an unreachable account-service or profile-service also
     * refunds.
     *
     * <p>{@code REJECTED} refunds the sender the amount held at initiation.
     *
     * <p>Safe to retry. Both money movements carry idempotency keys derived from the wire id, so a
     * call whose response was lost can be repeated without refunding or crediting twice.
     *
     * @param transactionId must name a wire currently in {@code PENDING_APPROVAL}; any other state
     *     is refused rather than re-resolved
     * @param payload the verdict; notes are shown to the customer
     * @return 200 with a plain-text confirmation naming the transaction and its new status
     * @throws org.springframework.web.server.ResponseStatusException with {@code NOT_FOUND} for an
     *     unknown transaction, or {@code BAD_REQUEST} when it is not awaiting review
     */
    @PatchMapping("/{transactionId}/fraud-status")
    public ResponseEntity<String> updateFraudStatus(
            @PathVariable UUID transactionId,
            @RequestBody @Valid FraudReviewUpdateDto payload) {

        fraudResolutionService.resolvePendingTransfer(transactionId, payload);

        return ResponseEntity.ok("Transaction " + transactionId + " successfully updated to " + payload.status());
    }
}

@Service
class FraudResolutionService {

    private static final String DESTINATION_CREDIT_PURPOSE = "destination-credit";
    private static final String SENDER_REFUND_PURPOSE = "sender-refund";

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final RecipientKycValidator recipientKycValidator;

    public FraudResolutionService(TransactionRepository transactionRepository,
                                  AccountServiceClient accountServiceClient,
                                  RecipientKycValidator recipientKycValidator) {
        this.transactionRepository = transactionRepository;
        this.accountServiceClient = accountServiceClient;
        this.recipientKycValidator = recipientKycValidator;
    }

    /**
     * Applies a reviewer's verdict to a held wire and records the outcome.
     *
     * <p>The transaction boundary covers only the local status and audit write. The credit or
     * refund is a remote call that commits in account-service independently, so a failure of this
     * commit afterwards leaves the money moved and the wire still in {@code PENDING_APPROVAL},
     * re-runnable. The two money movements therefore carry idempotency keys, and the keys must stay
     * distinct per purpose: a destination credit and a sender refund are different effects on the
     * same wire, and a single key derived from the wire id alone would make whichever ran second
     * look like a duplicate and be silently swallowed — money that should have moved not moving,
     * with nothing failing loudly.
     *
     * @param transactionId must name a wire in {@code PENDING_APPROVAL}
     * @param payload {@code status} already constrained to {@code APPROVED} or {@code REJECTED} by
     *     bean validation; any other value silently resolves nothing
     * @throws org.springframework.web.server.ResponseStatusException with {@code NOT_FOUND} for an
     *     unknown transaction or {@code BAD_REQUEST} when it is not awaiting review
     */
    @Transactional
    public void resolvePendingTransfer(UUID transactionId, InternalFraudController.FraudReviewUpdateDto payload) {

        TransactionEntity transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));

        if (transaction.getStatus() != TransactionStatus.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transaction is not in a PENDING_APPROVAL state");
        }

        if ("APPROVED".equals(payload.status())) {
            finalizeTransaction(transaction, payload.reviewerNotes());
        } else if ("REJECTED".equals(payload.status())) {
            reverseTransaction(transaction, payload.reviewerNotes());
        }
    }

    private void finalizeTransaction(TransactionEntity transaction, String reviewerNotes) {
        if (transaction.getDestinationAccountId() != null) {
            if (!destinationOwnerMayReceive(transaction.getDestinationAccountId())) {
                reverseUnreceivableTransaction(transaction, reviewerNotes);
                return;
            }

            accountServiceClient.credit(transaction.getDestinationAccountId(), new AccountServiceClient.CreditRequest(
                    transaction.getAmount(), "Incoming transfer from account " + transaction.getAccountId()
                            + " (wire " + transaction.getTransactionId() + ")",
                    idempotencyKeyFor(transaction, DESTINATION_CREDIT_PURPOSE)));
        }

        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setDescription(transaction.getDescription() + " [Fraud Review: APPROVED. Notes: " + reviewerNotes + "]");
        transactionRepository.save(transaction);
    }

    private boolean destinationOwnerMayReceive(Long destinationAccountId) {
        Long ownerUserId;
        try {
            AccountServiceClient.AccountOwnerResponse owner = accountServiceClient.lookupAccountOwner(destinationAccountId);
            if (owner == null || owner.ownerUserId() == null) {
                return false;
            }
            ownerUserId = owner.ownerUserId();
        } catch (RuntimeException e) {
            return false;
        }
        return recipientKycValidator.isApproved(ownerUserId);
    }

    private void reverseUnreceivableTransaction(TransactionEntity transaction, String reviewerNotes) {
        reverseTransaction(transaction,
                " [Fraud Review: APPROVED. Notes: " + reviewerNotes + "]"
                        + " [Reversed: recipient cannot receive funds - their identity verification was not"
                        + " approved at review time. This is NOT a fraud rejection.]",
                "Wire reversed - recipient cannot receive funds");
    }

    private void reverseTransaction(TransactionEntity transaction, String reviewerNotes) {
        reverseTransaction(transaction,
                " [Fraud Review: REJECTED. Notes: " + reviewerNotes + "]",
                "Wire reversed - fraud review rejected");
    }

    private void reverseTransaction(TransactionEntity transaction, String auditSuffix, String refundDescription) {
        transaction.setStatus(TransactionStatus.REJECTED);
        transaction.setDescription(transaction.getDescription() + auditSuffix);

        accountServiceClient.credit(transaction.getAccountId(), new AccountServiceClient.CreditRequest(
                transaction.getAmount(), refundDescription,
                idempotencyKeyFor(transaction, SENDER_REFUND_PURPOSE)));

        transactionRepository.save(transaction);
    }

    private static String idempotencyKeyFor(TransactionEntity transaction, String purpose) {
        return transaction.getTransactionId() + ":" + purpose;
    }
}
