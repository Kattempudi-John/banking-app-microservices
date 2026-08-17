package com.example.accountservice.controller;

import com.example.accountservice.mapper.AccountMapper;
import com.example.accountservice.model.AccountEntity;
import com.example.accountservice.model.AccountStatus;
import com.example.accountservice.model.TransactionEntity;
import com.example.accountservice.model.TransactionType;
import com.example.accountservice.repository.AccountRepository;
import com.example.accountservice.repository.TransactionRepository;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Service-to-service API over the balance ledger this service owns.
 *
 * <p>Everything here sits under {@code /api/v1/internal/} and is unauthenticated by design: there
 * is no end-user token on these hops, and the k8s ingress never publishes the prefix. That is the
 * whole of the protection, so two rules follow and must keep being followed. First, no endpoint may
 * be moved out from under that prefix — the batch balance endpoint once lived under
 * {@code /api/v1/accounts}, which the ingress does route publicly, and combined with the
 * {@code permitAll} its caller needs that made "post a list of user ids, get back their total
 * balances" reachable from the internet with no authentication at all. Second, each endpoint hands
 * back the minimum its caller needs rather than reusing a richer response type.
 *
 * <p>Since there is no token, the acting user is a request parameter here, and the service methods
 * behind these endpoints check ownership against it themselves.
 *
 * <p>The {@code @RequiresKyc} gate does not apply to anything in this file. Callers that need a
 * verification check — transaction-service, for instance — run it on their own side before calling.
 */
@RestController
public class InternalAccountController {

    private final InternalAccountService internalAccountService;
    private final AccountMapper accountMapper;

    public InternalAccountController(InternalAccountService internalAccountService, AccountMapper accountMapper) {
        this.internalAccountService = internalAccountService;
        this.accountMapper = accountMapper;
    }

    /**
     * Body of both transfer endpoints.
     *
     * @param userId required; the user whose ownership is checked on the source account
     * @param fromAccountId required; must differ from {@code toAccountId}, which is rejected rather
     *     than treated as a no-op
     * @param toAccountId required
     * @param amount required and strictly positive
     * @param idempotencyKey optional and deliberately not {@code @NotNull} — a {@code null} key
     *     behaves exactly as this endpoint always has, so no existing caller changes behaviour.
     *     Supply one and the same key can be replayed safely. Derive it from the thing being paid
     *     for, such as a transfer id, never from a fresh random value per attempt: a retry that
     *     invents a new key is not a retry as far as the ledger can tell and applies the money
     *     again
     */
    public record TransferRequest(
            @NotNull Long userId,
            @NotNull Long fromAccountId,
            @NotNull Long toAccountId,
            @NotNull @Positive BigDecimal amount,
            String idempotencyKey
    ) {}

    /**
     * Body of the debit endpoint.
     *
     * @param userId required; must own the account named in the path
     * @param amount required and strictly positive
     * @param description required; surfaces verbatim in the account holder's history
     * @param idempotencyKey optional, with the same replay semantics as on {@link TransferRequest}
     */
    public record DebitRequest(
            @NotNull Long userId,
            @NotNull @Positive BigDecimal amount,
            @NotNull String description,
            String idempotencyKey
    ) {}

    /**
     * Body of the credit endpoint.
     *
     * <p>Carries no {@code userId}: a credit is not ownership-checked, so any caller reaching this
     * endpoint can pay money into any account.
     *
     * @param amount required and strictly positive
     * @param description required; surfaces verbatim in the account holder's history
     * @param idempotencyKey optional, with the same replay semantics as on {@link TransferRequest};
     *     omitting it on a retried credit pays the customer twice
     */
    public record CreditRequest(
            @NotNull @Positive BigDecimal amount,
            @NotNull String description,
            String idempotencyKey
    ) {}

    /**
     * One user's summed balance across their non-closed accounts.
     *
     * @param userId the user the total belongs to
     * @param totalBalance the sum across that user's accounts
     */
    public record UserAggregateBalanceResponse(Long userId, BigDecimal totalBalance) {}

    /**
     * Answer to an IBAN lookup.
     *
     * @param accountId the internal id of the matched account
     * @param userId the owner's id
     * @param accountType the account type name
     * @param status the account status name; a match here does not imply the account is
     *     {@code ACTIVE}
     * @param swiftCode this platform's own BIC, not a per-account value — every account here shares
     *     it. It rides along so transaction-service can reject a wire whose BIC names a different
     *     bank from the one actually holding the IBAN. A BIC has no check digit, so no amount of
     *     format validation can catch a wrong-but-well-formed one; comparing it against the owning
     *     service's answer is the only thing that can
     */
    public record AccountLookupResponse(Long accountId, Long userId, String accountType, String status,
                                        String swiftCode) {}

    /**
     * Answer to an account-number lookup, shaped to be shown back to a sender.
     *
     * @param accountId the internal id needed to credit the recipient
     * @param ownerUserId the owner's id; account-service does not know anyone's name, so the caller
     *     resolves that through auth-service
     * @param accountType the account type name
     * @param maskedAccountNumber re-masked rather than echoed in full, so the response can be shown
     *     back to the sender as confirmation
     * @param status the account status name
     */
    public record RecipientLookupResponse(
            Long accountId,
            Long ownerUserId,
            String accountType,
            String maskedAccountNumber,
            String status
    ) {}

    /**
     * Answer to an owner lookup.
     *
     * @param ownerUserId the id of the user who owns the account, and nothing else
     */
    public record AccountOwnerResponse(Long ownerUserId) {}

    /**
     * Resolves an IBAN to the account holding it.
     *
     * <p>transaction-service calls this to decide whether an incoming wire's IBAN belongs to an
     * account on this platform: if it does, the wire can be executed as a real instant transfer
     * instead of the simulated debit-only external wire.
     *
     * @param iban the full IBAN as supplied by the sender; matched exactly, so formatting or case
     *     differences will not match
     * @return 200 with the account and this platform's BIC, or 404 when no account holds that IBAN
     */
    @GetMapping("/api/v1/internal/accounts/lookup")
    public ResponseEntity<AccountLookupResponse> lookupByIban(@RequestParam String iban) {
        return internalAccountService.findByIban(iban)
                .map(account -> ResponseEntity.ok(new AccountLookupResponse(
                        account.getId(), account.getUserId(), account.getAccountType().name(), account.getStatus().name(),
                        AccountMapper.PLATFORM_SWIFT_CODE)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Lists the account ids belonging to one user.
     *
     * <p>transaction-service's transfer history calls this to establish which account ids really
     * belong to the caller instead of trusting client-supplied ids for an authorization-sensitive
     * decision — the same "ask the owning service for the truth" pattern used for KYC checks
     * against profile-service.
     *
     * @param userId the user whose accounts are wanted; an unknown user yields an empty list and a
     *     200, not a 404
     * @return the ids of that user's non-closed accounts
     */
    @GetMapping("/api/v1/internal/accounts/by-user/{userId}")
    public ResponseEntity<List<Long>> getAccountIdsByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(internalAccountService.findAccountIdsByUser(userId));
    }

    /**
     * Moves money between two accounts the same user owns.
     *
     * <p>Both sides are ownership-checked against {@code userId}; use
     * {@link #transferToRecipient} when the destination belongs to someone else.
     *
     * <p>Answers 200 with an empty body on success and, importantly, also on a replay: a request
     * carrying an idempotency key already recorded is a no-op that reports success, and so is the
     * losing side of a race between two identical requests. A caller must not read 200 as "the
     * money moved this time", only as "the money has moved".
     *
     * @param request see {@link TransferRequest}; same-account transfers and insufficient funds are
     *     both 400, an unknown or unowned account 404 or 403
     * @return 200 with no body once the transfer has been applied, by this call or an earlier one
     */
    @PostMapping("/api/v1/internal/accounts/transfer")
    public ResponseEntity<Void> transfer(@RequestBody TransferRequest request) {
        applyOnce(() -> internalAccountService.transfer(
                request.userId(), request.fromAccountId(), request.toAccountId(), request.amount(), request.idempotencyKey()));
        return ResponseEntity.ok().build();
    }

    /**
     * Pays a different user's account from one the caller owns.
     *
     * <p>The same shape as {@link #transfer}, but ownership is enforced on the source only — the
     * destination belonging to somebody else is the point. The destination must still be
     * {@code ACTIVE}.
     *
     * <p>Replay semantics are identical to {@link #transfer}: a 200 means the transfer has landed,
     * not necessarily that this call is what landed it.
     *
     * @param request see {@link TransferRequest}; a destination that is not {@code ACTIVE} is a 400
     * @return 200 with no body once the transfer has been applied
     */
    @PostMapping("/api/v1/internal/accounts/transfer-to-recipient")
    public ResponseEntity<Void> transferToRecipient(@RequestBody TransferRequest request) {
        applyOnce(() -> internalAccountService.transferToRecipient(
                request.userId(), request.fromAccountId(), request.toAccountId(), request.amount(), request.idempotencyKey()));
        return ResponseEntity.ok().build();
    }

    /**
     * Resolves a full account number typed by a sender into the internal id needed to credit it.
     *
     * <p>Returns the owner's user id rather than their name — account-service has no idea who
     * anyone is, and the caller resolves that through auth-service — and re-masks the number so the
     * response can be echoed back to the sender as confirmation without repeating the whole thing.
     *
     * @param accountNumber the full unmasked number as the sender typed it; matched exactly
     * @return 200 with the recipient details, or 404 when no account carries that number
     */
    @GetMapping("/api/v1/internal/accounts/by-number/{accountNumber}")
    public ResponseEntity<RecipientLookupResponse> lookupByAccountNumber(@PathVariable String accountNumber) {
        return internalAccountService.findByAccountNumber(accountNumber)
                .map(account -> ResponseEntity.ok(new RecipientLookupResponse(
                        account.getId(),
                        account.getUserId(),
                        account.getAccountType().name(),
                        accountMapper.maskAccountNumber(account.getAccountNumber()),
                        account.getStatus().name())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Resolves an account id to the user who owns it.
     *
     * <p>Goes the opposite way from the two lookups above: transaction-service already holds an
     * account id and needs the person behind it, so it can re-ask profile-service for that person's
     * KYC status at the moment a fraud-held wire is actually credited, rather than trusting the
     * status read when the wire was first queued.
     *
     * <p>Hands back the owner id and nothing else on purpose. Reusing {@link AccountLookupResponse}
     * here would be less code but would publish balance-adjacent state to a caller that only asked
     * whose account it is.
     *
     * @param accountId the internal account id
     * @return 200 with the owner id, or 404 when the account does not exist
     */
    @GetMapping("/api/v1/internal/accounts/{accountId}/owner")
    public ResponseEntity<AccountOwnerResponse> lookupOwner(@PathVariable Long accountId) {
        return internalAccountService.findById(accountId)
                .map(account -> ResponseEntity.ok(new AccountOwnerResponse(account.getUserId())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Takes money out of one account.
     *
     * <p>Ownership-checked against the {@code userId} in the body. Replay semantics match
     * {@link #transfer}: a keyed request that has already been applied answers 200 without debiting
     * again.
     *
     * @param accountId must be owned by the request's {@code userId}
     * @param request see {@link DebitRequest}; a balance below the amount is a 400 and nothing is
     *     written
     * @return 200 with no body once the debit has been applied
     */
    @PostMapping("/api/v1/internal/accounts/{accountId}/debit")
    public ResponseEntity<Void> debit(@PathVariable Long accountId, @RequestBody DebitRequest request) {
        applyOnce(() -> internalAccountService.debit(
                request.userId(), accountId, request.amount(), request.description(), request.idempotencyKey()));
        return ResponseEntity.ok().build();
    }

    /**
     * Puts money into one account.
     *
     * <p>Not ownership-checked and not KYC-gated — any caller that can reach this path can credit
     * any account, which is why the path must stay unpublished. Replay semantics match
     * {@link #transfer}.
     *
     * @param accountId must reference an existing account; the status is not checked, so a
     *     non-{@code ACTIVE} account is still credited
     * @param request see {@link CreditRequest}
     * @return 200 with no body once the credit has been applied
     */
    @PostMapping("/api/v1/internal/accounts/{accountId}/credit")
    public ResponseEntity<Void> credit(@PathVariable Long accountId, @RequestBody CreditRequest request) {
        applyOnce(() -> internalAccountService.credit(
                accountId, request.amount(), request.description(), request.idempotencyKey()));
        return ResponseEntity.ok().build();
    }

    /**
     * Sums each listed user's balance across their non-closed accounts.
     *
     * <p>Must stay under {@code /api/v1/internal/}. It previously sat under {@code /api/v1/accounts},
     * which the k8s ingress routes publicly; together with the {@code permitAll} that
     * notification-service's call needs, that exposed every user's total balance to the internet
     * unauthenticated.
     *
     * @param userIds the users to total; a user with no accounts is simply absent from the result
     *     rather than reported as zero, so callers must not index the response positionally
     * @return one row per user that had at least one account
     */
    @PostMapping("/api/v1/internal/accounts/balances/batch")
    public ResponseEntity<List<UserAggregateBalanceResponse>> balancesBatch(@RequestBody List<Long> userIds) {
        return ResponseEntity.ok(internalAccountService.aggregateBalances(userIds));
    }

    private void applyOnce(Runnable ledgerWrite) {
        try {
            ledgerWrite.run();
        } catch (DataIntegrityViolationException ex) {
            if (!InternalAccountService.isDuplicateIdempotencyKey(ex)) {
                throw ex;
            }
        }
    }
}

@Service
class InternalAccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    InternalAccountService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Moves money between two accounts the same user owns.
     *
     * <p>Both accounts are ownership-checked, which is what separates this from
     * {@link #transferToRecipient}.
     *
     * <p>Everything below runs in one transaction: the idempotency check, both row locks, the
     * balance moves and both ledger rows commit or roll back together. Each account row is locked
     * {@code FOR UPDATE} before its balance is read, so the balance check cannot be split by a
     * concurrent transfer.
     *
     * <p>A same-account transfer is rejected <em>before</em> the two lookups, not after. Locking
     * the same id twice hands back the very same managed entity, so the subtract and the add cancel
     * out and the balance looks untouched — while a DEBIT and a CREDIT row are still both written
     * and the caller is told the transfer succeeded. Phantom history for money that never moved.
     *
     * <p>The idempotency key is stamped on the source (DEBIT) leg only. Stamping it on both legs
     * makes the unique index reject the transfer's own second row, so every keyed transfer would
     * fail outright on its first attempt; one leg is enough, because the two rows are written in
     * one transaction and finding that leg proves both landed.
     *
     * @param userId must own both accounts
     * @param fromAccountId must differ from {@code toAccountId}
     * @param toAccountId must differ from {@code fromAccountId}
     * @param amount positive; must not exceed the source's available balance
     * @param idempotencyKey optional — when it names a transaction already recorded, this call
     *     returns without moving money or writing a row; when {@code null} the call is applied
     *     unconditionally, so a retry moves the money a second time
     * @throws ResponseStatusException 400 for a same-account transfer or insufficient funds, 404
     *     when an account does not exist, 403 when either is owned by someone else
     * @throws DataIntegrityViolationException when a concurrent replay of the same key wins the
     *     race to insert; the caller is expected to treat that as already-applied
     */
    @Transactional
    public void transfer(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount, String idempotencyKey) {
        if (alreadyApplied(idempotencyKey)) {
            return;
        }

        rejectSameAccount(fromAccountId, toAccountId);

        AccountEntity fromAccount = lockAndVerifyOwnership(fromAccountId, userId);
        AccountEntity toAccount = lockAndVerifyOwnership(toAccountId, userId);

        moveFunds(fromAccount, toAccount, amount);

        recordTransaction(fromAccountId, TransactionType.DEBIT, amount, "Internal transfer to account " + toAccountId, idempotencyKey);
        recordTransaction(toAccountId, TransactionType.CREDIT, amount, "Internal transfer from account " + fromAccountId, null);
    }

    /**
     * Pays a different user's account.
     *
     * <p>Ownership is enforced on the source only — paying somebody else is the whole point — so
     * the destination is merely locked and must be open for business. A separate method rather than
     * a flag on {@link #transfer} so the own-accounts path keeps its stricter check and cannot be
     * loosened by accident.
     *
     * <p>Transaction boundary, row locking, same-account rejection and single-leg idempotency key
     * all behave exactly as on {@link #transfer}.
     *
     * @param userId must own {@code fromAccountId}; the destination's owner is not checked
     * @param fromAccountId must differ from {@code toAccountId}
     * @param toAccountId must exist and be {@code ACTIVE}; any other status is refused rather than
     *     queued
     * @param amount positive; must not exceed the source's available balance
     * @param idempotencyKey optional, with the same replay semantics as on {@link #transfer}
     * @throws ResponseStatusException 400 for a same-account transfer, insufficient funds or an
     *     inactive destination, 404 when an account does not exist, 403 when the source is owned by
     *     someone else
     * @throws DataIntegrityViolationException when a concurrent replay of the same key wins the
     *     race to insert
     */
    @Transactional
    public void transferToRecipient(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount, String idempotencyKey) {
        if (alreadyApplied(idempotencyKey)) {
            return;
        }

        rejectSameAccount(fromAccountId, toAccountId);

        AccountEntity fromAccount = lockAndVerifyOwnership(fromAccountId, userId);
        AccountEntity toAccount = accountRepository.findByIdForUpdate(toAccountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient account not found"));

        if (toAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That account can't receive transfers right now.");
        }

        moveFunds(fromAccount, toAccount, amount);

        recordTransaction(fromAccountId, TransactionType.DEBIT, amount, "Transfer to account " + toAccount.getAccountNumber(), idempotencyKey);
        recordTransaction(toAccountId, TransactionType.CREDIT, amount, "Transfer from account " + fromAccount.getAccountNumber(), null);
    }

    private void moveFunds(AccountEntity fromAccount, AccountEntity toAccount, BigDecimal amount) {
        if (fromAccount.getAvailableBalance().compareTo(amount) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_FUNDS");
        }

        fromAccount.setAvailableBalance(fromAccount.getAvailableBalance().subtract(amount));
        toAccount.setAvailableBalance(toAccount.getAvailableBalance().add(amount));
        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);
    }

    private void rejectSameAccount(Long fromAccountId, Long toAccountId) {
        if (fromAccountId.equals(toAccountId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT");
        }
    }

    /**
     * Takes money out of one account and records the movement as a single ledger entry.
     *
     * <p>The row is locked {@code FOR UPDATE} before the balance is read, and the balance write and
     * the ledger row share one transaction, so an insufficient-funds rejection leaves nothing
     * behind.
     *
     * @param userId must own {@code accountId}
     * @param accountId must reference an existing account
     * @param amount positive; a balance below it is refused rather than allowed to go negative
     * @param description recorded verbatim and visible to the account holder
     * @param idempotencyKey optional; a key already recorded makes this a no-op, {@code null}
     *     debits again on every retry
     * @throws ResponseStatusException 400 on insufficient funds, 404 when the account does not
     *     exist, 403 when it belongs to someone else
     * @throws DataIntegrityViolationException when a concurrent replay of the same key wins the
     *     race to insert
     */
    @Transactional
    public void debit(Long userId, Long accountId, BigDecimal amount, String description, String idempotencyKey) {
        if (alreadyApplied(idempotencyKey)) {
            return;
        }

        AccountEntity account = lockAndVerifyOwnership(accountId, userId);

        if (account.getAvailableBalance().compareTo(amount) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_FUNDS");
        }

        account.setAvailableBalance(account.getAvailableBalance().subtract(amount));
        accountRepository.save(account);
        recordTransaction(accountId, TransactionType.DEBIT, amount, description, idempotencyKey);
    }

    /**
     * Puts money into one account and records the movement as a single ledger entry.
     *
     * <p>No ownership check and no status check: whatever account the id names is credited. The row
     * is locked {@code FOR UPDATE} and the balance write and ledger row share one transaction.
     *
     * <p>This is the path that most needs an idempotency key. transaction-service issues these
     * credits as a remote call from inside its own local transaction, so a commit failure on its
     * side replays a credit that already succeeded here — and without a key the customer is paid
     * twice.
     *
     * @param accountId must reference an existing account
     * @param amount positive
     * @param description recorded verbatim and visible to the account holder
     * @param idempotencyKey optional; a key already recorded makes this a no-op, {@code null}
     *     credits again on every retry
     * @throws ResponseStatusException 404 when the account does not exist
     * @throws DataIntegrityViolationException when a concurrent replay of the same key wins the
     *     race to insert
     */
    @Transactional
    public void credit(Long accountId, BigDecimal amount, String description, String idempotencyKey) {
        if (alreadyApplied(idempotencyKey)) {
            return;
        }

        AccountEntity account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        account.setAvailableBalance(account.getAvailableBalance().add(amount));
        accountRepository.save(account);
        recordTransaction(accountId, TransactionType.CREDIT, amount, description, idempotencyKey);
    }

    private boolean alreadyApplied(String idempotencyKey) {
        return idempotencyKey != null && transactionRepository.existsByIdempotencyKey(idempotencyKey);
    }

    /**
     * Finds the account holding an IBAN.
     *
     * @param iban matched exactly, so case and spacing differences do not match
     * @return the account, or empty when none holds that IBAN
     */
    @Transactional(readOnly = true)
    public Optional<AccountEntity> findByIban(String iban) {
        return accountRepository.findByIban(iban);
    }

    /**
     * Finds the account carrying a full account number.
     *
     * @param accountNumber the raw unmasked number, matched exactly
     * @return the account, or empty when no account carries that number
     */
    @Transactional(readOnly = true)
    public Optional<AccountEntity> findByAccountNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber);
    }

    /**
     * Finds an account by its internal id.
     *
     * @param accountId the internal id
     * @return the account whatever its status, closed included, or empty when the id is unknown
     */
    @Transactional(readOnly = true)
    public Optional<AccountEntity> findById(Long accountId) {
        return accountRepository.findById(accountId);
    }

    /**
     * Lists the ids of a user's non-closed accounts.
     *
     * @param userId the owner; an unknown user yields an empty list rather than an error
     * @return the account ids, {@code CLOSED} accounts excluded
     */
    @Transactional(readOnly = true)
    public List<Long> findAccountIdsByUser(Long userId) {
        return accountRepository.findByUserIdAndStatusNot(userId, AccountStatus.CLOSED).stream()
                .map(AccountEntity::getId)
                .toList();
    }

    /**
     * Totals each listed user's balance in a single grouped query rather than per user.
     *
     * @param userIds the users to total; an empty list yields an empty result
     * @return one row per user that owns at least one account — users with none are absent
     *     altogether rather than present with a zero, so the result may be shorter than the input
     *     and must not be read positionally
     */
    @Transactional(readOnly = true)
    public List<InternalAccountController.UserAggregateBalanceResponse> aggregateBalances(List<Long> userIds) {
        return accountRepository.sumAvailableBalanceByUserIds(userIds).stream()
                .map(row -> new InternalAccountController.UserAggregateBalanceResponse((Long) row[0], (BigDecimal) row[1]))
                .toList();
    }

    private AccountEntity lockAndVerifyOwnership(Long accountId, Long userId) {
        AccountEntity account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        if (!account.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Both accounts must belong to the authenticated user");
        }
        return account;
    }

    private void recordTransaction(Long accountId, TransactionType type, BigDecimal amount, String description, String idempotencyKey) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setAccountId(accountId);
        transaction.setTransactionType(type);
        transaction.setAmount(amount);
        transaction.setDescription(description);
        transaction.setIdempotencyKey(idempotencyKey);
        transactionRepository.save(transaction);
    }

    private static final String IDEMPOTENCY_KEY_INDEX = "ux_transactions_idempotency_key";

    /**
     * Reports whether an integrity violation was the idempotency unique index rejecting a duplicate.
     *
     * <p>Must be told apart from every other integrity failure: treating them all as
     * "already applied" would report a credit that was actually refused — by the {@code account_id}
     * foreign key, say — back to the caller as a success, and the money would simply vanish.
     *
     * <p>The constraint name only ever appears in the driver's message, several causes down from
     * the Spring exception, so the whole cause chain is walked. The name it matches must stay in
     * step with the index created in {@code V8__Add_Idempotency_Key_To_Transactions.sql}; rename
     * the index without renaming it here and duplicate writes stop being recognised, surfacing the
     * loser of a race as a 500 to a caller that is merely retrying.
     *
     * @param exception the violation as Spring translated it; the cause chain is inspected, not
     *     just the top-level message
     * @return {@code true} only when the idempotency index is named somewhere in that chain
     */
    static boolean isDuplicateIdempotencyKey(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase().contains(IDEMPOTENCY_KEY_INDEX)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
