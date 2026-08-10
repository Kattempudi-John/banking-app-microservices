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

    public record TransferRequest(
            @NotNull Long userId,
            @NotNull Long fromAccountId,
            @NotNull Long toAccountId,
            @NotNull @Positive BigDecimal amount
    ) {}

    public record DebitRequest(
            @NotNull Long userId,
            @NotNull @Positive BigDecimal amount,
            @NotNull String description
    ) {}

    public record CreditRequest(
            @NotNull @Positive BigDecimal amount,
            @NotNull String description
    ) {}

    public record UserAggregateBalanceResponse(Long userId, BigDecimal totalBalance) {}

    public record AccountLookupResponse(Long accountId, Long userId, String accountType, String status) {}

    public record RecipientLookupResponse(
            Long accountId,
            Long ownerUserId,
            String accountType,
            String maskedAccountNumber,
            String status
    ) {}

    // transaction-service calls this to check whether an incoming wire's IBAN belongs to an
    // account on this platform - if it does, the wire can be executed as a real instant transfer
    // instead of the simulated debit-only external wire.
    @GetMapping("/api/v1/internal/accounts/lookup")
    public ResponseEntity<AccountLookupResponse> lookupByIban(@RequestParam String iban) {
        return internalAccountService.findByIban(iban)
                .map(account -> ResponseEntity.ok(new AccountLookupResponse(
                        account.getId(), account.getUserId(), account.getAccountType().name(), account.getStatus().name())))
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
        internalAccountService.transfer(request.userId(), request.fromAccountId(), request.toAccountId(), request.amount());
        return ResponseEntity.ok().build();
    }

    // Same shape as /transfer, but the destination is someone else's account - see
    // InternalAccountService.transferToRecipient for why that's a separate method rather than a flag.
    @PostMapping("/api/v1/internal/accounts/transfer-to-recipient")
    public ResponseEntity<Void> transferToRecipient(@RequestBody TransferRequest request) {
        internalAccountService.transferToRecipient(request.userId(), request.fromAccountId(), request.toAccountId(), request.amount());
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

    @PostMapping("/api/v1/internal/accounts/{accountId}/debit")
    public ResponseEntity<Void> debit(@PathVariable Long accountId, @RequestBody DebitRequest request) {
        internalAccountService.debit(request.userId(), accountId, request.amount(), request.description());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/internal/accounts/{accountId}/credit")
    public ResponseEntity<Void> credit(@PathVariable Long accountId, @RequestBody CreditRequest request) {
        internalAccountService.credit(accountId, request.amount(), request.description());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/accounts/balances/batch")
    public ResponseEntity<List<UserAggregateBalanceResponse>> balancesBatch(@RequestBody List<Long> userIds) {
        return ResponseEntity.ok(internalAccountService.aggregateBalances(userIds));
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
    public void transfer(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount) {
        rejectSameAccount(fromAccountId, toAccountId);

        AccountEntity fromAccount = lockAndVerifyOwnership(fromAccountId, userId);
        AccountEntity toAccount = lockAndVerifyOwnership(toAccountId, userId);

        moveFunds(fromAccount, toAccount, amount);

        recordTransaction(fromAccountId, TransactionType.DEBIT, amount, "Internal transfer to account " + toAccountId);
        recordTransaction(toAccountId, TransactionType.CREDIT, amount, "Internal transfer from account " + fromAccountId);
    }

    // Pays a DIFFERENT user's account. Ownership is enforced on the source only - the whole point is
    // that the destination belongs to someone else - so the destination is merely locked and must be
    // open for business. Deliberately a separate method rather than a flag on transfer() above, so
    // the own-accounts path keeps its stricter check and can't be loosened by accident.
    @Transactional
    public void transferToRecipient(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount) {
        rejectSameAccount(fromAccountId, toAccountId);

        AccountEntity fromAccount = lockAndVerifyOwnership(fromAccountId, userId);
        AccountEntity toAccount = accountRepository.findByIdForUpdate(toAccountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient account not found"));

        if (toAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That account can't receive transfers right now.");
        }

        moveFunds(fromAccount, toAccount, amount);

        recordTransaction(fromAccountId, TransactionType.DEBIT, amount, "Transfer to account " + toAccount.getAccountNumber());
        recordTransaction(toAccountId, TransactionType.CREDIT, amount, "Transfer from account " + fromAccount.getAccountNumber());
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
    public void debit(Long userId, Long accountId, BigDecimal amount, String description) {
        AccountEntity account = lockAndVerifyOwnership(accountId, userId);

        if (account.getAvailableBalance().compareTo(amount) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_FUNDS");
        }

        account.setAvailableBalance(account.getAvailableBalance().subtract(amount));
        accountRepository.save(account);
        recordTransaction(accountId, TransactionType.DEBIT, amount, description);
    }

    @Transactional
    public void credit(Long accountId, BigDecimal amount, String description) {
        AccountEntity account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        account.setAvailableBalance(account.getAvailableBalance().add(amount));
        accountRepository.save(account);
        recordTransaction(accountId, TransactionType.CREDIT, amount, description);
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

    private void recordTransaction(Long accountId, TransactionType type, BigDecimal amount, String description) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setAccountId(accountId);
        transaction.setTransactionType(type);
        transaction.setAmount(amount);
        transaction.setDescription(description);
        transactionRepository.save(transaction);
    }
}
