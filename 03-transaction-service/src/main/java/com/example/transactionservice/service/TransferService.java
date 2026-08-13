package com.example.transactionservice.service;

import com.example.transactionservice.annotation.RequiresKyc;
import com.example.transactionservice.client.AccountServiceClient;
import com.example.transactionservice.client.AuthServiceClient;
import com.example.transactionservice.dto.TransferResponseDto;
import com.example.transactionservice.event.FundsTransferredEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class TransferService {

    private final AccountServiceClient accountServiceClient;
    private final AuthServiceClient authServiceClient;
    private final RecipientKycValidator recipientKycValidator;
    private final ApplicationEventPublisher eventPublisher;

    public TransferService(AccountServiceClient accountServiceClient,
                           AuthServiceClient authServiceClient,
                           RecipientKycValidator recipientKycValidator,
                           ApplicationEventPublisher eventPublisher) {
        this.accountServiceClient = accountServiceClient;
        this.authServiceClient = authServiceClient;
        this.recipientKycValidator = recipientKycValidator;
        this.eventPublisher = eventPublisher;
    }

    // @RequiresKyc is a custom annotation, not a built in spring one, KycEnforcementAspect
    // intercepts any call to a method carrying this and blocks it before the body even starts
    // if the caller's kyc status is not approved, learned this is aop, aspect oriented programming
    // No recipient-side check here on purpose: both accounts belong to the caller (account-service
    // rejects the request otherwise), so the aspect above has already vetted the receiving user.
    @Transactional
    @RequiresKyc
    public TransferResponseDto executeTransfer(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount) {

        // account-service throws (and FeignErrorConfig's ErrorDecoder re-throws locally as a
        // ResponseStatusException) on insufficient funds, missing accounts, or ownership mismatch -
        // any of those propagate straight out of this call, aborting the transfer.
        accountServiceClient.transfer(new AccountServiceClient.TransferRequest(userId, fromAccountId, toAccountId, amount));

        // Generate a globally unique confirmation ID
        UUID transactionId = UUID.randomUUID();

        // Publish the domain event
        publishTransferEvent(userId, fromAccountId, toAccountId, amount, transactionId);

        // Return confirmation payload - always on-us, both accounts are on this platform by definition
        return new TransferResponseDto(transactionId, "COMPLETED", true);
    }

    // Pays an account belonging to a different user, identified by the account number the sender
    // typed. Same KYC gate and same post-commit event as executeTransfer above - only the ownership
    // rule on the destination differs, which is enforced over in account-service.
    @Transactional
    @RequiresKyc
    public TransferResponseDto executeTransferToRecipient(Long userId, Long fromAccountId,
                                                          String recipientAccountNumber, BigDecimal amount) {

        AccountServiceClient.RecipientLookupResponse recipient = resolveRecipient(recipientAccountNumber);

        // @RequiresKyc above only vouches for the sender. This is the one transfer path where the
        // money lands on someone else's account, so the receiving side gets checked too - and
        // before the transfer call below, since account-service debits and credits atomically and
        // there is no half of that to undo afterwards.
        recipientKycValidator.requireApprovedRecipient(recipient.ownerUserId());

        accountServiceClient.transferToRecipient(new AccountServiceClient.TransferRequest(
                userId, fromAccountId, recipient.accountId(), amount));

        UUID transactionId = UUID.randomUUID();

        // The destination account id is what the notification/alert path cares about, same as an
        // own-accounts transfer - the recipient being a different person doesn't change the event.
        publishTransferEvent(userId, fromAccountId, recipient.accountId(), amount, transactionId);

        return new TransferResponseDto(transactionId, "COMPLETED", true);
    }

    // Shared by the transfer itself and by the frontend's pre-send confirmation lookup, so both agree
    // on what counts as a valid recipient. The ErrorDecoder turns account-service's 404 into a
    // ResponseStatusException; rewriting it here gives the sender a message about the number they
    // typed rather than an internal "account not found".
    public AccountServiceClient.RecipientLookupResponse resolveRecipient(String recipientAccountNumber) {
        try {
            return accountServiceClient.lookupByAccountNumber(recipientAccountNumber);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No account found with that number. Double-check it with the person you're paying.");
            }
            throw e;
        }
    }

    // Best-effort: a recipient whose name can't be resolved is still payable, the sender just sees the
    // masked account number alone rather than a name to confirm against.
    public String resolveRecipientName(Long ownerUserId) {
        try {
            AuthServiceClient.DisplayNameResponse response = authServiceClient.getDisplayName(ownerUserId);
            return response != null ? response.displayName() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // Lets the pre-send confirmation lookup show the same verdict the transfer itself will reach,
    // as a flag instead of an exception - the sender finds out before typing an amount rather than
    // after pressing send.
    public boolean isRecipientVerified(Long ownerUserId) {
        return recipientKycValidator.isApproved(ownerUserId);
    }

    private void publishTransferEvent(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount, UUID transactionId) {
        // A @TransactionalEventListener will catch this and send it to Kafka strictly AFTER the commit
        // learned applicationeventpublisher.publishevent is spring's own in process event bus,
        // completely separate from kafka, this just hands the event off inside the jvm, some other
        // listener method elsewhere is the one that actually forwards it out to kafka afterward
        FundsTransferredEvent event = new FundsTransferredEvent(userId, fromAccountId, toAccountId, amount, transactionId);
        eventPublisher.publishEvent(event);
    }
}