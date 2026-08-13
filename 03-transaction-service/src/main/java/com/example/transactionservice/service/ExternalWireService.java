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

@Service
public class ExternalWireService {

    private final AccountServiceClient accountServiceClient;
    private final TransactionRepository transactionRepository;
    private final IbanSwiftValidator validator;
    private final RecipientKycValidator recipientKycValidator;
    private final KafkaTemplate<String, LargeTransferRequestedEvent> kafkaTemplate;

    // bigdecimal even for a constant threshold, comparing it later with compareTo instead of ==
    // or .equals(), learned bigdecimal.equals cares about scale too so 5000.00 vs 5000.0 would
    // not be equal even though they represent the same value, compareTo avoids that trap
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
        this.kafkaTemplate = kafkaTemplate; // Injected to publish high-value transfer events
    }

    @Transactional
    @RequiresKyc
    public TransferResponseDto initiateWire(Long userId, Long fromAccountId, ExternalWireRequestDto request) {

        // 1. Validate format
        validateFormat(request);

        // 2. Check whether this IBAN actually belongs to an account on this platform - if so,
        // this is really a peer-to-peer transfer between two real accounts, not money leaving to
        // a correspondent bank. A genuinely unresolved IBAN keeps today's simulated-external
        // behavior below untouched.
        Long destinationAccountId = resolveOnUsDestination(request.iban(), request.swiftCode());
        boolean onUsTransfer = destinationAccountId != null;

        UUID transactionId = UUID.randomUUID();

        // 3-4. Pre-reserve the funds: account-service locks the row, verifies ownership/funds,
        // debits it, and records the DEBIT transaction-history row, all atomically.
        accountServiceClient.debit(fromAccountId, new AccountServiceClient.DebitRequest(
                userId, request.amount(), "External Wire to " + request.beneficiaryName()));

        // 5. Threshold Check Logic
        TransactionStatus finalStatus = determineTransactionStatus(request.amount());

        // 6. An on-us wire that clears immediately completes its second leg right now; one that's
        // held for fraud review only gets credited to the destination later, once
        // FraudResolutionService approves it - mirroring how a held wire's reversal already works.
        if (onUsTransfer && finalStatus == TransactionStatus.COMPLETED) {
            accountServiceClient.credit(destinationAccountId, new AccountServiceClient.CreditRequest(
                    request.amount(), "Incoming transfer from account " + fromAccountId + " (wire " + transactionId + ")"));
        }

        // 7. Record the transaction state
        recordTransaction(transactionId, fromAccountId, request, finalStatus, destinationAccountId);

        // 8. Publish to Kafka if flagged for Fraud Review
        publishFraudReviewIfNeeded(transactionId, fromAccountId, request, finalStatus);

        // 9. Return the UUID, the resulting status (either COMPLETED or PENDING_APPROVAL), and
        // whether this actually stayed on-platform
        return new TransferResponseDto(transactionId, finalStatus.name(), onUsTransfer);
    }

    // Wording the caller sees when the lookup below can't be completed. Deliberately names no
    // internal service - which of our components was unreachable is our problem, not theirs, and
    // "try again" is the only action available to them. Same shape as the sender-side KYC gate's
    // message, for the same reason.
    private static final String WIRE_UNAVAILABLE_MESSAGE =
            "We couldn't complete your wire transfer right now. Please try again in a moment.";

    // Runs before the debit in step 3 above, so a wire aimed at an unverified account holder is
    // refused with nothing reserved.
    private Long resolveOnUsDestination(String iban, String submittedSwiftCode) {
        AccountServiceClient.AccountLookupResponse account;

        // The try scope is the lookup call and nothing else, deliberately. requireApprovedRecipient
        // below throws KycRequiredException for an unverified recipient, which has to surface as a
        // 403 - pulling it inside a catch of RuntimeException here would rewrite that verdict as a
        // 503 outage and quietly turn the recipient-KYC rule off while still looking like it ran.
        try {
            account = accountServiceClient.lookupByIban(iban);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return null; // No account on this platform has this IBAN - a genuinely external wire
            }
            // Any other status account-service answered with is it failing, not it saying "no such
            // IBAN" - we still don't know whether this wire is on-us, so we can't proceed.
            throw wireUnavailable(e);
        } catch (RuntimeException e) {
            // A connection failure never reaches FeignErrorConfig's ErrorDecoder at all (there is no
            // response to decode), so it arrives here as a raw Feign exception rather than a
            // ResponseStatusException. Left unhandled it became an HTTP 500, which reads as this
            // service crashing when in fact it correctly declined to guess at the destination.
            throw wireUnavailable(e);
        }

        if (account == null) {
            return null;
        }

        // An IBAN and a BIC on the same wire are supposed to name the same bank, and until now
        // nothing ever compared them: isValidIban runs a real mod-97 checksum, but isValidSwift is
        // a format regex and a BIC carries no check digit at all, so any well-formed string passed.
        // "XBUSUS33" is as valid a shape as "XBUSUS31" - a wire addressed to a bank that isn't this
        // one was landing in one of our accounts anyway, because the destination was resolved from
        // the IBAN alone and the BIC was never looked at again.
        // Checked before the KYC gate below on purpose: a wire naming the wrong bank is the
        // sender's own input error, and answering it with a 403 about the recipient would both
        // mis-describe the problem and disclose that person's verification state to someone who
        // addressed the wire incorrectly in the first place.
        requireSwiftMatchesIbanHolder(submittedSwiftCode, account.swiftCode());

        // The IBAN resolved, so this wire really lands on a platform user's account and the
        // receiving side is ours to vet - same rule as a transfer by account number.
        // The 404 branch above is the opposite case and stays deliberately unchecked: there is
        // no user here to look up, and another bank's customer is not ours to KYC.
        recipientKycValidator.requireApprovedRecipient(account.userId());
        return account.accountId();
    }

    // Reuses the sender-side gate's exception rather than introducing a second one: it already means
    // exactly "a dependency we needed an answer from didn't give us one, nothing moved", and
    // GlobalExceptionHandler already maps it to a 503 with the two-key body every client reads.
    // A parallel exception type plus a parallel handler would be two ways to say one thing, and the
    // next such failure would have to pick between them.
    private static KycEnforcementAspect.KycStatusUnavailableException wireUnavailable(RuntimeException cause) {
        return new KycEnforcementAspect.KycStatusUnavailableException(WIRE_UNAVAILABLE_MESSAGE, cause);
    }

    // Normalized the same way IbanSwiftValidator.isValidSwift normalizes before its regex - trimmed
    // and upper-cased - so a sender who types the right BIC in lower case is not refused for it.
    // Only reachable for an on-us IBAN: for a genuinely external one there is no directory to check
    // the pairing against, and refusing a correct BIC we simply cannot verify would be worse than
    // accepting it. That limitation is deliberate and documented rather than silently papered over.
    private void requireSwiftMatchesIbanHolder(String submittedSwiftCode, String expectedSwiftCode) {
        // No expected value means account-service answered without one - fail closed rather than
        // treat "we don't know this account's bank" as agreement with whatever the sender typed.
        if (expectedSwiftCode == null || submittedSwiftCode == null
                || !expectedSwiftCode.trim().equalsIgnoreCase(submittedSwiftCode.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That SWIFT/BIC code doesn't match the bank holding this IBAN. "
                            + "Check both with the person you're paying.");
        }
    }

    private void validateFormat(ExternalWireRequestDto request) {
        // Strict Formatting Validation
        if (!validator.isValidIban(request.iban())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid IBAN or SWIFT code format.");
        }
        if (!validator.isValidSwift(request.swiftCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid IBAN or SWIFT code format.");
        }
    }

    // compareTo returning greater than zero means amount is strictly bigger than the threshold,
    // so exactly 5000.00 itself does not trigger review, only amounts that go over it
    private TransactionStatus determineTransactionStatus(BigDecimal amount) {
        if (amount.compareTo(FRAUD_THRESHOLD) > 0) {
            return TransactionStatus.PENDING_APPROVAL; // Requires manual or automated review
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
        // Fire the event to the Fraud Detection Service
        kafkaTemplate.send(FRAUD_TOPIC, transactionId.toString(), event);
    }
}