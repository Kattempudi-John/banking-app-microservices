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

@RestController
public class InternalAccountController {

    private final InternalAccountService internalAccountService;
    private final AccountMapper accountMapper;

    public InternalAccountController(InternalAccountService internalAccountService, AccountMapper accountMapper) {
        this.internalAccountService = internalAccountService;
        this.accountMapper = accountMapper;
    }

    // idempotencyKey is OPTIONAL on all three requests, and deliberately not @NotNull: a null key has
    // to behave exactly as this endpoint always has, so no existing caller changes behaviour the day
    // this ships. Supply one and the same key can be replayed safely - see InternalAccountService.
    // Callers should derive it from the thing being paid for (a transfer id, say), never from a fresh
    // random value per attempt, since a retry that invents a new key is not a retry as far as the
    // ledger can tell and will simply apply the money again.
    public record TransferRequest(
            @NotNull Long userId,
            @NotNull Long fromAccountId,
            @NotNull Long toAccountId,
            @NotNull @Positive BigDecimal amount,
            String idempotencyKey
    ) {}

    public record DebitRequest(
            @NotNull Long userId,
            @NotNull @Positive BigDecimal amount,
            @NotNull String description,
            String idempotencyKey
    ) {}

    public record CreditRequest(
            @NotNull @Positive BigDecimal amount,
            @NotNull String description,
            String idempotencyKey
    ) {}

    public record UserAggregateBalanceResponse(Long userId, BigDecimal totalBalance) {}

    // swiftCode is this platform's own BIC, not a per-account value - every account here shares it.
    // It rides along so transaction-service can reject a wire whose BIC names a different bank than
    // the one actually holding the IBAN. A BIC has no check digit, so no amount of format validation
    // can catch a wrong-but-well-formed one; comparing it against the owning service's answer is the
    // only thing that can.
    public record AccountLookupResponse(Long accountId, Long userId, String accountType, String status,
                                        String swiftCode) {}

    public record RecipientLookupResponse(
            Long accountId,
            Long ownerUserId,
            String accountType,
            String maskedAccountNumber,
            String status
    ) {}

    public record AccountOwnerResponse(Long ownerUserId) {}

    // transaction-service calls this to check whether an incoming wire's IBAN belongs to an
    // account on this platform - if it does, the wire can be executed as a real instant transfer
    // instead of the simulated debit-only external wire.
    @GetMapping("/api/v1/internal/accounts/lookup")
    public ResponseEntity<AccountLookupResponse> lookupByIban(@RequestParam String iban) {
        return internalAccountService.findByIban(iban)
                .map(account -> ResponseEntity.ok(new AccountLookupResponse(
                        account.getId(), account.getUserId(), account.getAccountType().name(), account.getStatus().name(),
                        AccountMapper.PLATFORM_SWIFT_CODE)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // transaction-service's GET /api/v1/transfers (history) calls this to resolve which account
    // IDs actually belong to the caller, rather than trusting client-supplied account IDs for
    // something authorization-sensitive - the same "ask the owning service for the truth" pattern
    // already used for KYC checks against profile-service.
    @GetMapping("/api/v1/internal/accounts/by-user/{userId}")
    public ResponseEntity<List<Long>> getAccountIdsByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(internalAccountService.findAccountIdsByUser(userId));
    }

    @PostMapping("/api/v1/internal/accounts/transfer")
    public ResponseEntity<Void> transfer(@RequestBody TransferRequest request) {
        applyOnce(() -> internalAccountService.transfer(
                request.userId(), request.fromAccountId(), request.toAccountId(), request.amount(), request.idempotencyKey()));
        return ResponseEntity.ok().build();
    }

    // Same shape as /transfer, but the destination is someone else's account - see
    // InternalAccountService.transferToRecipient for why that's a separate method rather than a flag.
    @PostMapping("/api/v1/internal/accounts/transfer-to-recipient")
    public ResponseEntity<Void> transferToRecipient(@RequestBody TransferRequest request) {
        applyOnce(() -> internalAccountService.transferToRecipient(
                request.userId(), request.fromAccountId(), request.toAccountId(), request.amount(), request.idempotencyKey()));
        return ResponseEntity.ok().build();
    }

    // Resolves a full account number typed by a sender into the internal id needed to credit it.
    // Returns the owner's user id (not their name - account-service has no idea who anyone is; the
    // caller resolves that through auth-service) and re-masks the number so the response can be shown
    // back to the sender as confirmation without echoing the whole thing.
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

    // Goes the other way round from the two lookups above: transaction-service already holds an
    // account id and needs the owner behind it, so it can re-ask profile-service for that person's
    // KYC status at the moment a fraud-held wire is actually credited rather than trusting the
    // status read when the wire was first queued.
    // Returns the owner id and nothing else, for the same reason lookupByAccountNumber re-masks:
    // this controller is unauthenticated by design, so each endpoint hands back the minimum its
    // caller needs. Reusing AccountLookupResponse here would have been less code but would have
    // published balance-adjacent account state to a caller that only asked "whose is this?".
    @GetMapping("/api/v1/internal/accounts/{accountId}/owner")
    public ResponseEntity<AccountOwnerResponse> lookupOwner(@PathVariable Long accountId) {
        return internalAccountService.findById(accountId)
                .map(account -> ResponseEntity.ok(new AccountOwnerResponse(account.getUserId())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/v1/internal/accounts/{accountId}/debit")
    public ResponseEntity<Void> debit(@PathVariable Long accountId, @RequestBody DebitRequest request) {
        applyOnce(() -> internalAccountService.debit(
                request.userId(), accountId, request.amount(), request.description(), request.idempotencyKey()));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/internal/accounts/{accountId}/credit")
    public ResponseEntity<Void> credit(@PathVariable Long accountId, @RequestBody CreditRequest request) {
        applyOnce(() -> internalAccountService.credit(
                accountId, request.amount(), request.description(), request.idempotencyKey()));
        return ResponseEntity.ok().build();
    }

    // Under /api/v1/internal/ like everything else in this controller, and NOT under /api/v1/accounts.
    // It used to sit on the latter, which the k8s ingress routes publicly - combined with the
    // permitAll needed for notification-service to call it, that made "post a list of user ids, get
    // back their total balances" reachable from the internet with no authentication at all.
    @PostMapping("/api/v1/internal/accounts/balances/batch")
    public ResponseEntity<List<UserAggregateBalanceResponse>> balancesBatch(@RequestBody List<Long> userIds) {
        return ResponseEntity.ok(internalAccountService.aggregateBalances(userIds));
    }

    // The losing side of an idempotency-key race still answers 200. Its unique-index rejection rolled
    // the whole attempt back, so the balance moved once and exactly one row exists - which is the
    // outcome this caller asked for, just achieved by its twin. Reporting an error instead would push
    // the caller into retrying a write that has already landed.
    //
    // Caught out here rather than inside the @Transactional service method on purpose: once the index
    // rejects the insert that transaction is already marked rollback-only, so swallowing it in there
    // would rescue nothing and merely turn the failure into an UnexpectedRollbackException at commit
    // time. Out here the rollback has finished and there is nothing half-applied to reason about.
    private void applyOnce(Runnable ledgerWrite) {
        try {
            ledgerWrite.run();
        } catch (DataIntegrityViolationException ex) {
            // Narrowed to the idempotency index specifically. Treating EVERY integrity failure as
            // "already applied" would report a credit that was actually refused - by the account_id
            // foreign key, say - back to the caller as a success, and the money would simply vanish.
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

    // Moves money between two accounts the SAME user owns. Both sides are ownership-checked, which
    // is what separates this from transferToRecipient below.
    @Transactional
    public void transfer(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount, String idempotencyKey) {
        if (alreadyApplied(idempotencyKey)) {
            return;
        }

        rejectSameAccount(fromAccountId, toAccountId);

        AccountEntity fromAccount = lockAndVerifyOwnership(fromAccountId, userId);
        AccountEntity toAccount = lockAndVerifyOwnership(toAccountId, userId);

        moveFunds(fromAccount, toAccount, amount);

        // The key rides on the source leg only. Stamping it on BOTH legs was the obvious first move
        // and is wrong: the unique index would then reject the transfer's own second row, so every
        // keyed transfer would fail outright on its FIRST attempt. One leg carrying it is enough -
        // the two rows are written in one transaction, so finding that leg proves both landed.
        recordTransaction(fromAccountId, TransactionType.DEBIT, amount, "Internal transfer to account " + toAccountId, idempotencyKey);
        recordTransaction(toAccountId, TransactionType.CREDIT, amount, "Internal transfer from account " + fromAccountId, null);
    }

    // Pays a DIFFERENT user's account. Ownership is enforced on the source only - the whole point is
    // that the destination belongs to someone else - so the destination is merely locked and must be
    // open for business. Deliberately a separate method rather than a flag on transfer() above, so
    // the own-accounts path keeps its stricter check and can't be loosened by accident.
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

        // Source leg carries the key, same as transfer() above and for the same reason.
        recordTransaction(fromAccountId, TransactionType.DEBIT, amount, "Transfer to account " + toAccount.getAccountNumber(), idempotencyKey);
        recordTransaction(toAccountId, TransactionType.CREDIT, amount, "Transfer from account " + fromAccount.getAccountNumber(), null);
    }

    // Both accounts are already locked by the time this runs, so the read-check-write below is safe.
    private void moveFunds(AccountEntity fromAccount, AccountEntity toAccount, BigDecimal amount) {
        if (fromAccount.getAvailableBalance().compareTo(amount) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_FUNDS");
        }

        fromAccount.setAvailableBalance(fromAccount.getAvailableBalance().subtract(amount));
        toAccount.setAvailableBalance(toAccount.getAvailableBalance().add(amount));
        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);
    }

    // Has to happen before the two lookups, not after: locking the same id twice hands back the very
    // same managed entity from the persistence context, so the subtract and the add cancel out and the
    // balance looks untouched - while a DEBIT and a CREDIT row are still both written and the caller is
    // told the transfer succeeded. Phantom history for money that never moved.
    private void rejectSameAccount(Long fromAccountId, Long toAccountId) {
        if (fromAccountId.equals(toAccountId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT");
        }
    }

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

    // Returns true when this exact write has already landed, in which case the caller returns
    // straight away: no balance moved, no second row, no error. transaction-service issues these
    // credits as a remote call from inside its own local transaction, so a commit failure on its side
    // replays a credit that already succeeded here - and without this the customer is paid twice.
    //
    // Runs inside the caller's @Transactional, not before it, so the check and the insert it guards
    // share one transaction and one consistent snapshot. It is still only the fast path: two
    // simultaneous replays can both read "not there yet" before either inserts, and that last gap is
    // closed by the unique index in V8 rather than by anything written here.
    //
    // A null key skips the check entirely and every caller that passes none is byte-for-byte
    // unaffected - which is the point of making the key optional rather than required.
    private boolean alreadyApplied(String idempotencyKey) {
        return idempotencyKey != null && transactionRepository.existsByIdempotencyKey(idempotencyKey);
    }

    @Transactional(readOnly = true)
    public Optional<AccountEntity> findByIban(String iban) {
        return accountRepository.findByIban(iban);
    }

    @Transactional(readOnly = true)
    public Optional<AccountEntity> findByAccountNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber);
    }

    @Transactional(readOnly = true)
    public Optional<AccountEntity> findById(Long accountId) {
        return accountRepository.findById(accountId);
    }

    @Transactional(readOnly = true)
    public List<Long> findAccountIdsByUser(Long userId) {
        return accountRepository.findByUserIdAndStatusNot(userId, AccountStatus.CLOSED).stream()
                .map(AccountEntity::getId)
                .toList();
    }

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
        // Null for every caller that supplied no key. Postgres counts NULLs as distinct under a
        // unique index, so any number of unkeyed rows coexist happily while two rows naming the same
        // key cannot - which is what makes the column safe to add without backfilling anything.
        transaction.setIdempotencyKey(idempotencyKey);
        transactionRepository.save(transaction);
    }

    // Must match the index name in V8__Add_Idempotency_Key_To_Transactions.sql. Rename the index
    // without renaming this and duplicate writes stop being recognised - the loser of a race would
    // surface as a 500 to a caller that is simply retrying.
    private static final String IDEMPOTENCY_KEY_INDEX = "ux_transactions_idempotency_key";

    // The constraint name only ever appears in the driver's message, several causes down from the
    // Spring exception, so the whole chain is walked. Matching on the name rather than on the
    // exception type alone is what keeps an unrelated integrity failure from being mistaken for a
    // duplicate and reported to the caller as a success - see InternalAccountController.applyOnce.
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
