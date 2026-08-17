package com.example.transactionservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;

/**
 * Moves money and resolves account identity through account-service, the sole owner of balances.
 *
 * <p>Every call here is a remote hop. None of them enlists in this service's transaction: a money
 * movement that returns success has committed in account-service and stays committed even when the
 * local transaction that requested it rolls back afterwards. Callers that cannot tolerate that must
 * pass an idempotency key and be safe to replay, or must not put the call inside a transaction.
 *
 * <p>Failures arrive in two different shapes. An HTTP error status is translated by
 * {@code FeignErrorConfig}'s {@code ErrorDecoder} into a {@code ResponseStatusException} carrying
 * the remote status, so a 404 can be told apart from a real failure. A connection failure or
 * timeout never reaches the decoder — there is no response to decode — and surfaces as a raw Feign
 * {@code RuntimeException} instead. Code that only catches {@code ResponseStatusException} will let
 * an outage through as an HTTP 500.
 */
@FeignClient(name = "account-service", url = "${application.client.account-service.url:http://localhost:8083}")
public interface AccountServiceClient {

    /**
     * Instruction to move funds between two accounts in one atomic account-service operation.
     *
     * @param userId the sender, used to authorise the source account; never {@code null}
     * @param fromAccountId debited account, must be owned by {@code userId}
     * @param toAccountId credited account; ownership rules differ per endpoint, see
     *     {@link #transfer} and {@link #transferToRecipient}
     * @param amount positive; insufficient funds are rejected remotely, not clamped
     * @param idempotencyKey optional, but a repeated call without one moves the money a second
     *     time; must be stable across retries of the same logical movement and distinct from every
     *     other movement, or a genuinely different effect gets swallowed as a duplicate
     */
    record TransferRequest(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount,
                           String idempotencyKey) {
        /**
         * Builds a request with no idempotency key, accepting that a retry debits twice.
         *
         * @param userId the sender, used to authorise the source account
         * @param fromAccountId debited account, must be owned by {@code userId}
         * @param toAccountId credited account
         * @param amount positive
         */
        public TransferRequest(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount) {
            this(userId, fromAccountId, toAccountId, amount, null);
        }
    }

    /**
     * Instruction to take funds out of one account.
     *
     * @param userId the account owner, used to authorise the debit; never {@code null}
     * @param amount positive; the remote side rejects the call when the locked balance is short
     * @param description free text recorded on the resulting ledger row and visible to the user
     * @param idempotencyKey optional; without one a repeat debits again, and there is no automatic
     *     compensating credit for the first
     */
    record DebitRequest(Long userId, BigDecimal amount, String description, String idempotencyKey) {
        /**
         * Builds a debit with no idempotency key, accepting that a retry debits twice.
         *
         * @param userId the account owner, used to authorise the debit
         * @param amount positive
         * @param description free text recorded on the ledger row
         */
        public DebitRequest(Long userId, BigDecimal amount, String description) {
            this(userId, amount, description, null);
        }
    }

    /**
     * Instruction to put funds into one account.
     *
     * <p>Carries no user id: a credit is authorised by the caller having decided the money is owed,
     * not by the recipient's session, and the recipient never has one here.
     *
     * @param amount positive
     * @param description free text recorded on the resulting ledger row and visible to the user
     * @param idempotencyKey optional; without one a repeat credits again, creating money that was
     *     never debited
     */
    record CreditRequest(BigDecimal amount, String description, String idempotencyKey) {
        /**
         * Builds a credit with no idempotency key, accepting that a retry credits twice.
         *
         * @param amount positive
         * @param description free text recorded on the ledger row
         */
        public CreditRequest(BigDecimal amount, String description) {
            this(amount, description, null);
        }
    }

    /**
     * Account resolved from an IBAN.
     *
     * @param accountId internal id, the only form the credit and debit endpoints accept
     * @param userId the owner, whose KYC standing the caller is responsible for checking
     * @param accountType product label, for display only
     * @param status account lifecycle state as account-service records it
     * @param swiftCode BIC of the bank that actually holds this IBAN, authoritative because it
     *     comes from the service owning account identity; comparing a sender's submitted BIC
     *     against this value is the only way to catch a wire addressed to the wrong bank, since a
     *     BIC has no check digit and every well-formed string validates
     */
    record AccountLookupResponse(Long accountId, Long userId, String accountType, String status,
                                 String swiftCode) {}

    /**
     * Account resolved from a full account number typed by a sender.
     *
     * @param accountId internal id used to credit the account
     * @param ownerUserId the recipient, who must pass a KYC check before money is sent
     * @param accountType product label, for display only
     * @param maskedAccountNumber safe to show the sender back; the full number is never returned
     * @param status account lifecycle state as account-service records it
     */
    record RecipientLookupResponse(Long accountId, Long ownerUserId, String accountType,
                                   String maskedAccountNumber, String status) {}

    /**
     * Owner of an account already identified by its internal id.
     *
     * @param ownerUserId may be {@code null} if account-service cannot name an owner, which callers
     *     must treat as "cannot establish the recipient" rather than as no owner being required
     */
    record AccountOwnerResponse(Long ownerUserId) {}

    /**
     * Transfers between two accounts that both belong to {@code userId}.
     *
     * <p>Ownership of both sides is enforced remotely, which is what lets the caller skip a
     * recipient-side KYC check on this path: the receiving user is the sending user.
     *
     * @param request both account ids must be owned by {@code request.userId()}
     */
    @PostMapping("/api/v1/internal/accounts/transfer")
    void transfer(@RequestBody TransferRequest request);

    /**
     * Transfers to an account belonging to somebody else.
     *
     * <p>Same payload as {@link #transfer}, but only the source side is ownership-checked remotely.
     * The destination is expected to belong to another user, so vetting that user is entirely the
     * caller's responsibility and must happen before this call — the debit and credit commit
     * together and there is no half of it to undo.
     *
     * @param request {@code fromAccountId} must be owned by {@code request.userId()};
     *     {@code toAccountId} is not checked against it
     */
    @PostMapping("/api/v1/internal/accounts/transfer-to-recipient")
    void transferToRecipient(@RequestBody TransferRequest request);

    /**
     * Resolves a full account number into the internal account id needed to credit it.
     *
     * @param accountNumber the number as the sender typed it, never blank
     * @return the recipient's account; a 404 arrives as a {@code ResponseStatusException} with
     *     {@code NOT_FOUND}, which callers rewrite into a message about the number that was typed
     */
    @GetMapping("/api/v1/internal/accounts/by-number/{accountNumber}")
    RecipientLookupResponse lookupByAccountNumber(@PathVariable("accountNumber") String accountNumber);

    /**
     * Resolves an internal account id back to its owner.
     *
     * <p>The reverse direction of the two lookups above, which start from something the sender
     * typed. Needed on the fraud-review credit path, where the only thing recorded about a held
     * wire's destination is its account id and the recipient still has to be re-vetted before the
     * money lands.
     *
     * @param accountId internal id previously recorded on a transaction, never {@code null}
     * @return the owner; a 404 arrives as a {@code ResponseStatusException} with {@code NOT_FOUND}
     *     when the account no longer exists
     */
    @GetMapping("/api/v1/internal/accounts/{accountId}/owner")
    AccountOwnerResponse lookupAccountOwner(@PathVariable("accountId") Long accountId);

    /**
     * Takes funds out of an account, locking the row and recording a ledger entry atomically.
     *
     * @param accountId internal id of the account to debit, never {@code null}
     * @param request rejected remotely on insufficient funds or ownership mismatch, which surface
     *     locally as a {@code ResponseStatusException}
     */
    @PostMapping("/api/v1/internal/accounts/{accountId}/debit")
    void debit(@PathVariable("accountId") Long accountId, @RequestBody DebitRequest request);

    /**
     * Puts funds into an account, locking the row and recording a ledger entry atomically.
     *
     * @param accountId internal id of the account to credit, never {@code null}
     * @param request pass an idempotency key whenever this credit is the second leg of a movement
     *     that could be re-driven, otherwise a replay creates money
     */
    @PostMapping("/api/v1/internal/accounts/{accountId}/credit")
    void credit(@PathVariable("accountId") Long accountId, @RequestBody CreditRequest request);

    /**
     * Resolves an IBAN to an account on this platform, if one holds it.
     *
     * @param iban the IBAN as submitted, already format-validated by the caller
     * @return the matching account; a 404 arrives as a {@code ResponseStatusException} with
     *     {@code NOT_FOUND} and means "no account here holds this IBAN", which callers read as a
     *     genuinely external wire rather than as a failure — any other status means account-service
     *     itself is failing and the on-us question is still unanswered
     */
    @GetMapping("/api/v1/internal/accounts/lookup")
    AccountLookupResponse lookupByIban(@RequestParam("iban") String iban);

    /**
     * Lists the account ids owned by a user.
     *
     * <p>Backs the transfer-history endpoint's authorisation check, which resolves ownership here
     * rather than trusting the account ids a client supplies.
     *
     * @param userId the caller's own id from the JWT, never {@code null}
     * @return ids owned by that user; empty for a user with no accounts, which callers treat as an
     *     empty history rather than an error
     */
    @GetMapping("/api/v1/internal/accounts/by-user/{userId}")
    List<Long> getAccountIdsByUser(@PathVariable("userId") Long userId);
}
