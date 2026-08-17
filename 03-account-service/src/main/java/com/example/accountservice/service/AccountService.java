package com.example.accountservice.service;

import com.example.accountservice.annotation.RequiresKyc;
import com.example.accountservice.dto.AccountOverviewResponseDto;
import com.example.accountservice.mapper.AccountMapper;
import com.example.accountservice.model.AccountEntity;
import com.example.accountservice.model.AccountStatus;
import com.example.accountservice.model.AccountType;
import com.example.accountservice.model.TransactionEntity;
import com.example.accountservice.model.TransactionType;
import com.example.accountservice.repository.AccountRepository;
import com.example.accountservice.repository.TransactionRepository;
import com.example.accountservice.util.IbanGenerator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Serves the account-owner's own view of their accounts and applies the customer-facing writes.
 *
 * <p>Reads are the default here: the class-level transaction boundary is read-only, so Hibernate
 * skips dirty checking for the query methods, and each write method re-declares a read-write
 * boundary of its own. A method that forgets to do so cannot persist anything.
 *
 * <p>Every method takes the caller's {@code userId} and verifies ownership itself rather than
 * trusting the account id it was handed; nothing here is safe to call with an id sourced from a
 * client without that check.
 *
 * <p>The three write methods are gated by {@link RequiresKyc}, enforced by
 * {@code KycEnforcementAspect} on the Spring proxy. That gate therefore applies to calls arriving
 * from the controller, not to a call this class makes to itself.
 */
@Service
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AccountMapper accountMapper;
    private final IbanGenerator ibanGenerator;
    private final SecureRandom random = new SecureRandom();

    public AccountService(AccountRepository accountRepository,
                          TransactionRepository transactionRepository,
                          AccountMapper accountMapper,
                          IbanGenerator ibanGenerator) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.accountMapper = accountMapper;
        this.ibanGenerator = ibanGenerator;
    }

    /**
     * Lists the accounts to show on a user's dashboard.
     *
     * <p>{@code CLOSED} accounts are excluded, so a user who has closed everything gets an empty
     * list rather than a history of dead accounts. Account numbers come back masked.
     *
     * @param userId the authenticated caller's id; an id with no accounts yields an empty list, not
     *     a 404
     * @return one DTO per non-closed account, never {@code null}
     */
    public List<AccountOverviewResponseDto> getDashboardAccounts(Long userId) {
        List<AccountEntity> accounts = accountRepository.findByUserIdAndStatusNot(userId, AccountStatus.CLOSED);

        return accounts.stream()
                .map(accountMapper::toOverviewDto)
                .collect(Collectors.toList());
    }

    /**
     * Pages the transactions of a single account.
     *
     * <p>Ownership is checked before anything is read, so an account id belonging to someone else
     * is rejected rather than returning an empty page that would leak whether the id exists.
     *
     * @param userId the authenticated caller's id; must own {@code accountId}
     * @param accountId must reference an existing account, closed or not
     * @param filterType {@code null} means both directions; otherwise restricts to that single
     *     {@code CREDIT} or {@code DEBIT} type
     * @param pageable sort and page size as supplied by the caller; no ordering is imposed here, so
     *     an unsorted value yields database order
     * @return the requested page, empty when the account has no matching transactions
     * @throws ResponseStatusException 404 when the account does not exist
     * @throws AccessDeniedException when the account exists but belongs to another user
     */
    public Page<TransactionEntity> getAccountTransactions(Long userId, Long accountId, TransactionType filterType, Pageable pageable) {

        verifyAccountOwnership(userId, accountId);

        if (filterType != null) {
            return transactionRepository.findByAccountIdAndTransactionType(accountId, filterType, pageable);
        } else {
            return transactionRepository.findByAccountId(accountId, pageable);
        }
    }

    /**
     * Pages transactions across every account the caller owns, optionally narrowed to one of them.
     *
     * <p>Unlike {@link #getAccountTransactions}, the scope is the user rather than a single
     * account, which is what lets the History page show everything in one list. The candidate
     * account ids are resolved from the caller's own non-closed accounts, so a client-supplied
     * {@code accountIdFilter} can only ever narrow that set, never widen it.
     *
     * @param userId the authenticated caller's id
     * @param accountIdFilter {@code null} spans all of the caller's accounts; otherwise must be one
     *     the caller owns
     * @param type {@code null} means both directions; otherwise restricts to that single type
     * @param from inclusive lower bound on transaction time, or {@code null} for unbounded
     * @param to inclusive upper bound on transaction time, or {@code null} for unbounded
     * @param pageable sort and page size as supplied by the caller
     * @return the requested page; an empty page when the caller owns no open accounts, without
     *     touching the transaction table
     * @throws AccessDeniedException when {@code accountIdFilter} names an account the caller does
     *     not own
     */
    public Page<TransactionEntity> getAllTransactions(Long userId, Long accountIdFilter, TransactionType type,
                                                        LocalDateTime from, LocalDateTime to, Pageable pageable) {
        List<Long> ownedAccountIds = accountRepository.findByUserIdAndStatusNot(userId, AccountStatus.CLOSED).stream()
                .map(AccountEntity::getId)
                .collect(Collectors.toList());

        List<Long> accountIds;
        if (accountIdFilter != null) {
            if (!ownedAccountIds.contains(accountIdFilter)) {
                throw new AccessDeniedException("Action forbidden: You do not have permission to view this account's history.");
            }
            accountIds = List.of(accountIdFilter);
        } else {
            accountIds = ownedAccountIds;
        }

        if (accountIds.isEmpty()) {
            return Page.empty(pageable);
        }

        return transactionRepository.findByAccountIdInWithFilters(accountIds, type, from, to, pageable);
    }

    private static final BigDecimal MAX_DEPOSIT_AMOUNT = new BigDecimal("10000");

    /**
     * Credits an account from the self-service Add Funds action and records the movement.
     *
     * <p>Stands in for what a real product would fund through a linked card or ACH pull, so it is
     * capped at {@code 10000} per call and ownership-checked — unlike the unauthenticated internal
     * credit endpoint that other services use, which is neither.
     *
     * <p>KYC-gated: taking money in is precisely what know-your-customer exists to govern, so an
     * unverified identity can no more pay money in than move it out. The gate runs before this
     * method body, so a refused call writes nothing.
     *
     * <p>The account row is locked {@code FOR UPDATE} before its balance is read, so a concurrent
     * transfer cannot interleave between the read and the write. The balance update and the ledger
     * row commit together; neither survives alone.
     *
     * @param userId the authenticated caller's id; must own {@code accountId}
     * @param accountId must reference an existing account
     * @param amount must be strictly positive and no greater than {@code 10000}; zero is rejected
     *     rather than ignored
     * @return the account's refreshed overview, reflecting the new balance
     * @throws ResponseStatusException 400 when the amount is non-positive or above the cap, 404
     *     when the account does not exist
     * @throws AccessDeniedException when the account belongs to another user
     */
    @RequiresKyc
    @Transactional
    public AccountOverviewResponseDto depositFunds(Long userId, Long accountId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Deposit amount must be positive");
        }
        if (amount.compareTo(MAX_DEPOSIT_AMOUNT) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Deposit amount cannot exceed " + MAX_DEPOSIT_AMOUNT);
        }

        AccountEntity account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        if (!account.getUserId().equals(userId)) {
            throw new AccessDeniedException("Action forbidden: You do not have permission to deposit into this account.");
        }

        account.setAvailableBalance(account.getAvailableBalance().add(amount));
        accountRepository.save(account);

        TransactionEntity transaction = new TransactionEntity();
        transaction.setAccountId(accountId);
        transaction.setTransactionType(TransactionType.CREDIT);
        transaction.setAmount(amount);
        transaction.setDescription("Deposit (self-service demo)");
        transactionRepository.save(transaction);

        return accountMapper.toOverviewDto(account);
    }

    private static final String DEFAULT_ROUTING_NUMBER = "021000021";
    private static final int MAX_ACCOUNTS_PER_USER = 5;

    /**
     * Opens an additional account for a user, {@code ACTIVE} with a zero balance.
     *
     * <p>An ordinary banking feature rather than a demo shortcut, so it is not behind
     * {@code app.demo.enabled}. A user may hold at most five non-closed accounts, which exists only
     * to stop one user from creating accounts without limit; closing an account frees a slot.
     *
     * <p>KYC-gated for the classic reason — opening an account is the know-your-customer moment.
     * This covers the self-service path only: the starter account a brand-new user receives at
     * registration is built directly by {@code UserRegisteredListener} and never passes through
     * here, so provisioning still works while that user sits at {@code PENDING_VERIFICATION}.
     *
     * <p>The account number is generated, and its IBAN derived from it, inside the same transaction
     * as the insert, so a rolled-back attempt leaves no number reserved.
     *
     * @param userId the authenticated caller's id; the new account is owned by this user
     * @param accountType the product to open; determines nothing but the stored type, all accounts
     *     open at a zero balance
     * @return the newly created account's overview
     * @throws ResponseStatusException 400 when the caller already holds five non-closed accounts
     */
    @RequiresKyc
    @Transactional
    public AccountOverviewResponseDto openAccount(Long userId, AccountType accountType) {
        long existingCount = accountRepository.findByUserIdAndStatusNot(userId, AccountStatus.CLOSED).size();
        if (existingCount >= MAX_ACCOUNTS_PER_USER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account limit reached");
        }

        AccountEntity account = new AccountEntity();
        account.setUserId(userId);
        account.setAccountType(accountType);
        account.setAvailableBalance(BigDecimal.ZERO);
        account.setRoutingNumber(DEFAULT_ROUTING_NUMBER);
        String accountNumber = generateAccountNumber();
        account.setAccountNumber(accountNumber);
        account.setIban(ibanGenerator.generate(DEFAULT_ROUTING_NUMBER, accountNumber));
        account.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(account);

        return accountMapper.toOverviewDto(account);
    }

    private String generateAccountNumber() {
        long number = 100_000_000_000L + (long) (random.nextDouble() * 900_000_000_000L);
        return String.valueOf(number);
    }

    private static final String[] DEMO_CREDIT_DESCRIPTIONS = {
            "Payroll Deposit", "Freelance Payment", "Refund - Online Order", "Interest Payment"
    };
    private static final String[] DEMO_DEBIT_DESCRIPTIONS = {
            "Grocery Store", "Coffee Shop", "Electric Bill", "Online Purchase - Amazon",
            "Gas Station", "Restaurant", "Streaming Subscription", "Pharmacy"
    };

    /**
     * Fabricates a plausible transaction history on an account and adjusts its balance to match.
     *
     * <p>Demo-only, so that an account that has only ever been deposited into does not show a
     * single flat line. Writes two or three credits and up to nine debits, dated randomly across
     * the past 45 days. The caller is responsible for refusing this when {@code app.demo.enabled}
     * is false; this method does not check it.
     *
     * <p>Real money moves: the balance is genuinely written, not simulated. Seeded debits are
     * capped at half of what the balance would be once the seeded credits land, so the account can
     * never be drained or driven negative, and the debit loop stops early when that budget runs
     * out — so fewer rows than requested is a normal outcome, not a failure.
     *
     * <p>KYC-gated despite being demo-only and off in production, because the history it writes
     * includes credits; leaving it open would be the deposit gate with an extra step.
     *
     * <p>The account row is locked {@code FOR UPDATE} for the duration, and all the generated rows
     * plus the new balance commit as one unit.
     *
     * @param userId the authenticated caller's id; must own {@code accountId}
     * @param accountId must reference an existing account
     * @return the account's refreshed overview, reflecting the adjusted balance
     * @throws ResponseStatusException 404 when the account does not exist
     * @throws AccessDeniedException when the account belongs to another user
     */
    @RequiresKyc
    @Transactional
    public AccountOverviewResponseDto seedDemoTransactions(Long userId, Long accountId) {
        AccountEntity account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        if (!account.getUserId().equals(userId)) {
            throw new AccessDeniedException("Action forbidden: You do not have permission to modify this account.");
        }

        List<TransactionEntity> generated = new ArrayList<>();

        BigDecimal creditsSum = BigDecimal.ZERO;
        int creditCount = 2 + random.nextInt(2);
        for (int i = 0; i < creditCount; i++) {
            BigDecimal amount = randomAmount(800, 2500);
            creditsSum = creditsSum.add(amount);
            generated.add(buildDemoTransaction(accountId, TransactionType.CREDIT, amount, randomChoice(DEMO_CREDIT_DESCRIPTIONS)));
        }

        BigDecimal maxDebitBudget = account.getAvailableBalance().add(creditsSum)
                .multiply(new BigDecimal("0.5"));
        BigDecimal debitsSum = BigDecimal.ZERO;
        int debitCount = 5 + random.nextInt(5);
        for (int i = 0; i < debitCount; i++) {
            BigDecimal amount = randomAmount(5, 150);
            if (debitsSum.add(amount).compareTo(maxDebitBudget) > 0) {
                break;
            }
            debitsSum = debitsSum.add(amount);
            generated.add(buildDemoTransaction(accountId, TransactionType.DEBIT, amount, randomChoice(DEMO_DEBIT_DESCRIPTIONS)));
        }

        transactionRepository.saveAll(generated);

        account.setAvailableBalance(account.getAvailableBalance().add(creditsSum).subtract(debitsSum));
        accountRepository.save(account);

        return accountMapper.toOverviewDto(account);
    }

    private TransactionEntity buildDemoTransaction(Long accountId, TransactionType type, BigDecimal amount, String description) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setAccountId(accountId);
        transaction.setTransactionType(type);
        transaction.setAmount(amount);
        transaction.setDescription(description);
        transaction.setCreatedAt(randomPastDateTime());
        return transaction;
    }

    private BigDecimal randomAmount(int min, int max) {
        double value = min + random.nextDouble() * (max - min);
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private String randomChoice(String[] options) {
        return options[random.nextInt(options.length)];
    }

    private LocalDateTime randomPastDateTime() {
        return LocalDateTime.now()
                .minusDays(1 + random.nextInt(45))
                .minusHours(random.nextInt(24))
                .minusMinutes(random.nextInt(60));
    }

    private void verifyAccountOwnership(Long userId, Long accountId) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found or invalid ID provided."));

        if (!account.getUserId().equals(userId)) {
            throw new AccessDeniedException("Action forbidden: You do not have permission to view this account's history.");
        }
    }
}
