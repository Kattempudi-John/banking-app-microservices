package com.example.transactionservice.service;

import com.example.transactionservice.annotation.RequiresKyc;
import com.example.transactionservice.aspect.KycEnforcementAspect;
import com.example.transactionservice.client.AccountServiceClient;
import com.example.transactionservice.dto.ExternalWireRequestDto;
import com.example.transactionservice.dto.TransferResponseDto;
import com.example.transactionservice.event.LargeTransferRequestedEvent;
import com.example.transactionservice.model.TransactionEntity;
import com.example.transactionservice.model.TransactionStatus;
import com.example.transactionservice.repository.TransactionRepository;
import com.example.transactionservice.util.IbanSwiftValidator;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Initiates outgoing wire transfers, including ones that turn out to land back on this platform.
 *
 * <p>This is the only path that writes a row to this service's transaction table, and the only one
 * that can leave money in an unfinished state: a wire above the review threshold is debited from
 * the sender immediately and held until {@code FraudResolutionService} either credits the
 * destination or refunds it.
 *
 * <p>Wires publish nothing to the {@code successful-transfers} topic, even when the destination
 * turns out to be an account on this platform. Only the ledger transfer paths in
 * {@code TransferService} do.
 */
@Service
public class ExternalWireService {

    private final AccountServiceClient accountServiceClient;
    private final TransactionRepository transactionRepository;
    private final IbanSwiftValidator validator;
    private final RecipientKycValidator recipientKycValidator;
    private final KafkaTemplate<String, LargeTransferRequestedEvent> kafkaTemplate;

    private static final BigDecimal FRAUD_THRESHOLD = new BigDecimal("5000.00");
    private static final String FRAUD_TOPIC = "large-transfers-review";

    public ExternalWireService(AccountServiceClient accountServiceClient,
                               TransactionRepository transactionRepository,
                               IbanSwiftValidator validator,
                               RecipientKycValidator recipientKycValidator,
                               KafkaTemplate<String, LargeTransferRequestedEvent> kafkaTemplate) {
        this.accountServiceClient = accountServiceClient;
        this.transactionRepository = transactionRepository;
        this.validator = validator;
        this.recipientKycValidator = recipientKycValidator;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Debits the sender and records an outgoing wire, holding it for review above the threshold.
     *
     * <p>The review threshold is a compile-time constant of {@code 5000.00} — not configurable, not
     * per-customer — and the comparison is strictly greater-than, so a wire of exactly
     * {@code 5000.00} clears immediately and {@code 5000.01} does not. Anything held is published to
     * {@code large-transfers-review} and stays in {@code PENDING_APPROVAL}, with the sender's funds
     * already gone, until a reviewer resolves it.
     *
     * <p>The IBAN is first tested against this platform's own accounts. When it resolves, the wire
     * is really an on-us payment: the submitted BIC must match the bank actually holding that IBAN
     * (checked here because a BIC has no check digit and format validation alone cannot catch a
     * wrong one), and the receiving user's identity verification is checked before any money moves.
     * When the IBAN resolves nowhere the wire is treated as genuinely external and neither check
     * applies — there is no directory to verify a foreign BIC against and another bank's customer is
     * not ours to vet. A lookup that fails for any reason other than "not found" refuses the wire
     * rather than guessing.
     *
     * <p>The transaction boundary covers only the local row write. The debit, and the on-us credit
     * that may follow it, are remote calls to account-service that commit there independently and
     * are not rolled back when this method's transaction is: a failure after the debit — including
     * a failure of the local commit itself — leaves the sender debited with no compensating credit
     * and no record of the wire. No idempotency key is supplied on either call, so retrying a wire
     * whose outcome is unknown debits a second time. Resolving that state today is a manual
     * operation.
     *
     * @param userId taken from the JWT, never client-supplied; must own {@code fromAccountId}
     * @param fromAccountId debited account, must be owned by {@code userId}
     * @param request IBAN must pass mod-97 and the BIC must be 8 or 11 characters, both rejected as
     *     400 before anything moves; {@code amount} positive, and compared against the threshold
     *     with {@code BigDecimal.compareTo} so trailing-zero scale differences do not affect the
     *     verdict
     * @return the generated wire id, the resulting status — {@code COMPLETED} or
     *     {@code PENDING_APPROVAL}, never anything else — and whether the wire actually stayed on
     *     this platform
     * @throws org.springframework.web.server.ResponseStatusException with {@code BAD_REQUEST} for a
     *     malformed IBAN or BIC, or for a BIC that does not match the bank holding an on-us IBAN;
     *     also relayed from account-service for insufficient funds or ownership failures
     * @throws com.example.transactionservice.aspect.KycEnforcementAspect.KycRequiredException when
     *     the sender is unverified, or when an on-us destination's owner is
     * @throws com.example.transactionservice.aspect.KycEnforcementAspect.KycStatusUnavailableException
     *     when the destination lookup cannot be completed, meaning nothing moved and the caller
     *     should retry
     */
    @Transactional
    @RequiresKyc
    public TransferResponseDto initiateWire(Long userId, Long fromAccountId, ExternalWireRequestDto request) {

        validateFormat(request);

        Long destinationAccountId = resolveOnUsDestination(request.iban(), request.swiftCode());
        boolean onUsTransfer = destinationAccountId != null;

        UUID transactionId = UUID.randomUUID();

        accountServiceClient.debit(fromAccountId, new AccountServiceClient.DebitRequest(
                userId, request.amount(), "External Wire to " + request.beneficiaryName()));

        TransactionStatus finalStatus = determineTransactionStatus(request.amount());

        if (onUsTransfer && finalStatus == TransactionStatus.COMPLETED) {
            accountServiceClient.credit(destinationAccountId, new AccountServiceClient.CreditRequest(
                    request.amount(), "Incoming transfer from account " + fromAccountId + " (wire " + transactionId + ")"));
        }

        recordTransaction(transactionId, fromAccountId, request, finalStatus, destinationAccountId);

        publishFraudReviewIfNeeded(transactionId, fromAccountId, request, finalStatus);

        return new TransferResponseDto(transactionId, finalStatus.name(), onUsTransfer);
    }

    private static final String WIRE_UNAVAILABLE_MESSAGE =
            "We couldn't complete your wire transfer right now. Please try again in a moment.";

    private Long resolveOnUsDestination(String iban, String submittedSwiftCode) {
        AccountServiceClient.AccountLookupResponse account;

        try {
            account = accountServiceClient.lookupByIban(iban);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return null;
            }
            throw wireUnavailable(e);
        } catch (RuntimeException e) {
            throw wireUnavailable(e);
        }

        if (account == null) {
            return null;
        }

        requireSwiftMatchesIbanHolder(submittedSwiftCode, account.swiftCode());

        recipientKycValidator.requireApprovedRecipient(account.userId());
        return account.accountId();
    }

    private static KycEnforcementAspect.KycStatusUnavailableException wireUnavailable(RuntimeException cause) {
        return new KycEnforcementAspect.KycStatusUnavailableException(WIRE_UNAVAILABLE_MESSAGE, cause);
    }

    private void requireSwiftMatchesIbanHolder(String submittedSwiftCode, String expectedSwiftCode) {
        if (expectedSwiftCode == null || submittedSwiftCode == null
                || !expectedSwiftCode.trim().equalsIgnoreCase(submittedSwiftCode.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That SWIFT/BIC code doesn't match the bank holding this IBAN. "
                            + "Check both with the person you're paying.");
        }
    }

    private void validateFormat(ExternalWireRequestDto request) {
        if (!validator.isValidIban(request.iban())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid IBAN or SWIFT code format.");
        }
        if (!validator.isValidSwift(request.swiftCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid IBAN or SWIFT code format.");
        }
    }

    private TransactionStatus determineTransactionStatus(BigDecimal amount) {
        if (amount.compareTo(FRAUD_THRESHOLD) > 0) {
            return TransactionStatus.PENDING_APPROVAL;
        }
        return TransactionStatus.COMPLETED;
    }

    private void recordTransaction(UUID transactionId, Long fromAccountId, ExternalWireRequestDto request,
                                    TransactionStatus status, Long destinationAccountId) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setTransactionId(transactionId);
        transaction.setAccountId(fromAccountId);
        transaction.setAmount(request.amount());
        transaction.setStatus(status);
        transaction.setDescription("External Wire to " + request.beneficiaryName());
        transaction.setIban(request.iban());
        transaction.setSwiftCode(request.swiftCode());
        transaction.setBeneficiaryName(request.beneficiaryName());
        transaction.setDestinationAccountId(destinationAccountId);
        transactionRepository.save(transaction);
    }

    private void publishFraudReviewIfNeeded(UUID transactionId, Long fromAccountId, ExternalWireRequestDto request, TransactionStatus status) {
        if (status != TransactionStatus.PENDING_APPROVAL) {
            return;
        }
        LargeTransferRequestedEvent event = new LargeTransferRequestedEvent(
                transactionId,
                fromAccountId,
                request.amount(),
                request.iban(),
                request.swiftCode(),
                request.beneficiaryName()
        );
        kafkaTemplate.send(FRAUD_TOPIC, transactionId.toString(), event);
    }
}
