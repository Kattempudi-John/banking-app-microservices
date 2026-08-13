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

// no @PreAuthorize on this one unlike the customer facing controllers, learned this is meant
// to only ever be reachable from inside the cluster network, not directly from an end user
@RestController
@RequestMapping("/api/v1/internal/transfers")
public class InternalFraudController {

    private final FraudResolutionService fraudResolutionService;

    public InternalFraudController(FraudResolutionService fraudResolutionService) {
        this.fraudResolutionService = fraudResolutionService;
    }

    public record FraudReviewUpdateDto(
            @NotBlank(message = "Status is required")
            @Pattern(regexp = "^(APPROVED|REJECTED)$", message = "Status must be APPROVED or REJECTED")
            String status,
            
            String reviewerNotes
    ) {}

    @PatchMapping("/{transactionId}/fraud-status")
    public ResponseEntity<String> updateFraudStatus(
            @PathVariable UUID transactionId,
            @RequestBody @Valid FraudReviewUpdateDto payload) {
        
        fraudResolutionService.resolvePendingTransfer(transactionId, payload);
        
        return ResponseEntity.ok("Transaction " + transactionId + " successfully updated to " + payload.status());
    }
}

// learned a top level class does not have to be public, and a file can hold more than one
// top level class as long as only one of them (the one matching the filename) is public,
// this service is only ever used by the controller right above it so package private is enough
@Service
class FraudResolutionService {

    // Both money movements below happen inside a local @Transactional method, so the remote call can
    // succeed and the commit that records it can still fail afterwards - leaving the row in
    // PENDING_APPROVAL and the resolution re-runnable, which is a second real refund or a second
    // real credit. These keys make account-service apply each effect at most once no matter how
    // many times we ask.
    //
    // They MUST stay distinct. The destination credit and the sender refund are two different
    // effects on the same wire, so a single key derived from the transaction id alone would make
    // whichever ran second look like a duplicate of the first and be swallowed - money that should
    // have moved silently not moving, which is worse than the bug being fixed because nothing fails
    // loudly. Suffixing by purpose keeps them stable across retries and different from each other.
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
        // Funds were already deducted during initiation. For a genuinely external wire that's all
        // that's needed - the money conceptually left the platform. For an on-us wire that was
        // held for review, the destination account never got its half of the transfer yet - credit
        // it now, completing the second leg (same account-service call the immediate-complete path
        // in ExternalWireService already makes for on-us wires that don't need review).
        if (transaction.getDestinationAccountId() != null) {
            // ExternalWireService vetted the recipient at initiation, but a held wire can sit here
            // for days and a verification can be revoked or reversed in the meantime. The rule has
            // to hold at the till as well as at the door, so re-check it against the recipient's
            // status right now rather than trusting the initiation-time verdict.
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

    // Fails closed on every answer that isn't a positive "this user is approved": no owner on the
    // response, account-service 404ing the account, or account-service being unreachable all count
    // as "cannot establish that the recipient may receive this money". Rejected the alternative of
    // letting the lookup failure propagate - that aborts the whole @Transactional resolution and
    // leaves the wire in PENDING_APPROVAL with the sender's money still reserved, which is the one
    // outcome worse than either crediting or refunding.
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
        // isApproved rather than requireApprovedRecipient: the thrown form would 403 the reviewer's
        // own request and roll this resolution back, when what's wanted is a decided outcome here.
        return recipientKycValidator.isApproved(ownerUserId);
    }

    // Same refund as a fraud rejection, deliberately a different audit trail. The reviewer said
    // APPROVED and it would be a false record to file this under their rejection - what stopped the
    // money was the receiving side, after the review, so the description says both: the reviewer's
    // actual verdict, then the separate reason the funds went back.
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

    // The refund itself, shared by both reversal reasons above - only the audit wording differs, so
    // the money movement lives in exactly one place.
    private void reverseTransaction(TransactionEntity transaction, String auditSuffix, String refundDescription) {
        // Atomic Reversal Logic: Return the reserved funds to the user.
        transaction.setStatus(TransactionStatus.REJECTED);
        transaction.setDescription(transaction.getDescription() + auditSuffix);

        // account-service locks the row and adds the amount back atomically, same as the
        // original debit did - it's the sole owner of the accounts table now.
        // Both reversal reasons share this one key on purpose: a wire is reversed for exactly one
        // of them, and either way it is the same single refund of the same held amount.
        accountServiceClient.credit(transaction.getAccountId(), new AccountServiceClient.CreditRequest(
                transaction.getAmount(), refundDescription,
                idempotencyKeyFor(transaction, SENDER_REFUND_PURPOSE)));

        transactionRepository.save(transaction);
    }

    // The wire's own id is the stable part - it is the same on every retry of the same resolution
    // and different for every other wire - and the purpose is what keeps the two effects on that one
    // wire from colliding. Rejected a fresh UUID per call, which would be unique but not stable, so
    // a retry would look like a new effect and refund twice: exactly the bug this is here to close.
    private static String idempotencyKeyFor(TransactionEntity transaction, String purpose) {
        return transaction.getTransactionId() + ":" + purpose;
    }
}