package com.example.transactionservice.service;

import com.example.transactionservice.annotation.RequiresKyc;
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
    private final KafkaTemplate<String, LargeTransferRequestedEvent> kafkaTemplate;

    // bigdecimal even for a constant threshold, comparing it later with compareTo instead of ==
    // or .equals(), learned bigdecimal.equals cares about scale too so 5000.00 vs 5000.0 would
    // not be equal even though they represent the same value, compareTo avoids that trap
    private static final BigDecimal FRAUD_THRESHOLD = new BigDecimal("5000.00");
    private static final String FRAUD_TOPIC = "large-transfers-review";

    public ExternalWireService(AccountServiceClient accountServiceClient,
                               TransactionRepository transactionRepository,
                               IbanSwiftValidator validator,
                               KafkaTemplate<String, LargeTransferRequestedEvent> kafkaTemplate) {
        this.accountServiceClient = accountServiceClient;
        this.transactionRepository = transactionRepository;
        this.validator = validator;
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
        Long destinationAccountId = resolveOnUsDestination(request.iban());
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

    private Long resolveOnUsDestination(String iban) {
        try {
            AccountServiceClient.AccountLookupResponse account = accountServiceClient.lookupByIban(iban);
            return account != null ? account.accountId() : null;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return null; // No account on this platform has this IBAN - a genuinely external wire
            }
            throw e;
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