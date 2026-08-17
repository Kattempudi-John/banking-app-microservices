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

/**
 * Executes transfers between accounts that both live on this platform.
 *
 * <p>These are the only two paths that publish {@code FundsTransferredEvent}, and therefore the
 * only ones that reach the {@code successful-transfers} topic; wires are handled by
 * {@code ExternalWireService} and announce nothing there.
 *
 * <p>Neither path writes to this service's own transaction table. The authoritative ledger row is
 * created by account-service, and the transaction id returned here is a confirmation reference
 * generated locally per call — it does not identify a row in this service's database.
 */
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

    /**
     * Moves funds between two accounts that both belong to the caller.
     *
     * <p>Gated on the caller's identity verification before the body starts. No recipient-side check
     * is made or needed: account-service rejects the request unless both accounts belong to
     * {@code userId}, so the receiving user is the sending user and has already been vetted.
     *
     * <p>The whole movement is one remote call that account-service applies atomically, so this
     * method has no partially-applied state of its own. The local transaction boundary exists to
     * hold the transfer event until commit — the announcement to Kafka is emitted after commit, and
     * never at all if this method throws.
     *
     * <p>No idempotency key is passed, so a retry of a call whose response was lost transfers a
     * second time.
     *
     * @param userId taken from the JWT, never client-supplied; must own both accounts
     * @param fromAccountId debited account, must be owned by {@code userId}
     * @param toAccountId credited account, must also be owned by {@code userId}
     * @param amount positive; insufficient funds are refused remotely, not clamped
     * @return confirmation carrying a freshly generated id, always status {@code COMPLETED} and
     *     always on-us, since by definition both accounts are on this platform
     * @throws org.springframework.web.server.ResponseStatusException relayed from account-service
     *     for insufficient funds, a missing account, or an ownership mismatch; the transfer is
     *     abandoned with nothing moved
     * @throws com.example.transactionservice.aspect.KycEnforcementAspect.KycRequiredException when
     *     the caller is not verified
     */
    @Transactional
    @RequiresKyc
    public TransferResponseDto executeTransfer(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount) {

        accountServiceClient.transfer(new AccountServiceClient.TransferRequest(userId, fromAccountId, toAccountId, amount));

        UUID transactionId = UUID.randomUUID();

        publishTransferEvent(userId, fromAccountId, toAccountId, amount, transactionId);

        return new TransferResponseDto(transactionId, "COMPLETED", true);
    }

    /**
     * Pays an account belonging to another user, identified by the account number the sender typed.
     *
     * <p>Both parties are verified on this path. The caller-side gate runs before the body; the
     * recipient is then checked explicitly, and deliberately before the money call, because
     * account-service debits and credits atomically and there would be no half of it to undo
     * afterwards. A recipient whose status cannot be established at all is refused rather than
     * assumed good.
     *
     * <p>Publishes the same event as an own-accounts transfer: the recipient being a different
     * person does not change what downstream consumers need.
     *
     * <p>No idempotency key is passed, so a retry of a call whose response was lost pays twice.
     *
     * @param userId taken from the JWT, never client-supplied; must own {@code fromAccountId}
     * @param fromAccountId debited account, must be owned by {@code userId}
     * @param recipientAccountNumber full account number as typed, never blank; resolved to an
     *     internal id remotely
     * @param amount positive; insufficient funds are refused remotely
     * @return confirmation carrying a freshly generated id, always status {@code COMPLETED} and
     *     always on-us
     * @throws org.springframework.web.server.ResponseStatusException with {@code NOT_FOUND} when no
     *     account holds that number, or relayed from account-service for funds and ownership
     *     failures
     * @throws com.example.transactionservice.aspect.KycEnforcementAspect.KycRequiredException when
     *     either the sender or the recipient is unverified
     */
    @Transactional
    @RequiresKyc
    public TransferResponseDto executeTransferToRecipient(Long userId, Long fromAccountId,
                                                          String recipientAccountNumber, BigDecimal amount) {

        AccountServiceClient.RecipientLookupResponse recipient = resolveRecipient(recipientAccountNumber);

        recipientKycValidator.requireApprovedRecipient(recipient.ownerUserId());

        accountServiceClient.transferToRecipient(new AccountServiceClient.TransferRequest(
                userId, fromAccountId, recipient.accountId(), amount));

        UUID transactionId = UUID.randomUUID();

        publishTransferEvent(userId, fromAccountId, recipient.accountId(), amount, transactionId);

        return new TransferResponseDto(transactionId, "COMPLETED", true);
    }

    /**
     * Resolves a typed account number to the recipient account it names.
     *
     * <p>Shared by the transfer itself and by the pre-send confirmation lookup so both agree on what
     * counts as a valid recipient. A remote 404 is rewritten into a message about the number the
     * sender typed rather than passed on as an internal "account not found"; every other failure is
     * rethrown unchanged.
     *
     * @param recipientAccountNumber full account number as typed, never blank
     * @return the resolved account, never {@code null}; carries a masked number only, never the
     *     full one
     * @throws org.springframework.web.server.ResponseStatusException with {@code NOT_FOUND} when no
     *     account holds that number
     */
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

    /**
     * Returns a recipient's display name for the sender to confirm against.
     *
     * <p>Best effort by design: a name that cannot be resolved never blocks a payment, the sender
     * simply confirms against the masked account number alone.
     *
     * @param ownerUserId owner id from an account lookup, never client-supplied
     * @return the name, or {@code null} when auth-service has none or cannot be reached — callers
     *     must handle the absent case rather than treat it as a failure
     */
    public String resolveRecipientName(Long ownerUserId) {
        try {
            AuthServiceClient.DisplayNameResponse response = authServiceClient.getDisplayName(ownerUserId);
            return response != null ? response.displayName() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Reports whether a recipient could currently be paid.
     *
     * <p>Answers the same question {@link #executeTransferToRecipient} enforces, but as a flag
     * rather than an exception, so the pre-send lookup can warn the sender before they type an
     * amount. The verdict is not held: a recipient verified here can still be refused at send time,
     * and an outage reports as unverified.
     *
     * @param ownerUserId owner id from an account lookup, never client-supplied
     * @return {@code true} only when the recipient is approved right now
     */
    public boolean isRecipientVerified(Long ownerUserId) {
        return recipientKycValidator.isApproved(ownerUserId);
    }

    private void publishTransferEvent(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount, UUID transactionId) {
        FundsTransferredEvent event = new FundsTransferredEvent(userId, fromAccountId, toAccountId, amount, transactionId);
        eventPublisher.publishEvent(event);
    }
}
