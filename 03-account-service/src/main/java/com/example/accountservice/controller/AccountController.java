package com.example.accountservice.controller;

import com.example.accountservice.dto.AccountOverviewResponseDto;
import com.example.accountservice.model.AccountType;
import com.example.accountservice.model.TransactionEntity;
import com.example.accountservice.model.TransactionType;
import com.example.accountservice.service.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Serves the authenticated account owner's own accounts and transaction history.
 *
 * <p>Every endpoint here requires a fully authenticated token — {@code SCOPE_FULL_AUTH}, meaning
 * the holder has cleared two-factor as well as password login, so a half-authenticated token that
 * can still reach the login flow cannot reach any of this. The acting user is always taken from
 * that token's {@code userId} claim, never from the request body or path, so no endpoint here can
 * be pointed at somebody else's account by a client.
 *
 * <p>Distinct from {@code InternalAccountController}: that one is unauthenticated and meant for
 * other services, this one is the public, per-user API.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@PreAuthorize("hasAuthority('SCOPE_FULL_AUTH')")
public class AccountController {

    private final AccountService accountService;

    @Value("${app.demo.enabled:false}")
    private boolean demoModeEnabled;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Returns the caller's accounts for the dashboard.
     *
     * <p>Closed accounts are omitted; a caller with none gets an empty list and a 200, not a 404.
     *
     * @return every non-closed account owned by the caller, account numbers masked
     */
    @GetMapping
    public ResponseEntity<List<AccountOverviewResponseDto>> getAccountsOverview() {
        Long userId = extractUserIdFromAuth();

        List<AccountOverviewResponseDto> accounts = accountService.getDashboardAccounts(userId);

        return ResponseEntity.ok(accounts);
    }

    /**
     * Body of a self-service open-account request.
     *
     * @param accountType required; the product to open
     */
    public record OpenAccountRequest(@NotNull AccountType accountType) {}

    /**
     * Opens an additional account for the caller.
     *
     * <p>An ordinary banking feature, not a demo-only shortcut, so it stays available when
     * {@code app.demo.enabled} is false.
     *
     * <p>Requires the caller's KYC status to be {@code APPROVED}: a 403 comes back when it is not,
     * and a 503 when identity verification could not be checked at all. The new account is created
     * {@code ACTIVE} with a zero balance, and the caller is capped at five non-closed accounts.
     *
     * @param request must name an account type; a missing type is rejected as a 400 before any work
     *     is done
     * @return the newly opened account, in the same shape the dashboard uses
     */
    @PostMapping
    public ResponseEntity<AccountOverviewResponseDto> openAccount(@Valid @RequestBody OpenAccountRequest request) {
        Long userId = extractUserIdFromAuth();

        AccountOverviewResponseDto created = accountService.openAccount(userId, request.accountType());

        return ResponseEntity.ok(created);
    }

    /**
     * Pages one account's transaction history.
     *
     * <p>Scoped to the single account named in the path; use {@link #getAllTransactionsAcrossAccounts}
     * for a view spanning everything the caller owns.
     *
     * <p>Responds with the standard Spring {@code Page} JSON — a {@code content} array alongside
     * {@code totalPages} and {@code totalElements} — rather than a custom wrapper.
     *
     * @param accountId must be owned by the caller; an account belonging to someone else is a 403
     *     and a nonexistent one a 404
     * @param type optional; omit for both directions, or pass {@code CREDIT} / {@code DEBIT} to
     *     narrow
     * @param pageable built by Spring Data from {@code page}, {@code size} and {@code sort} query
     *     parameters; defaults to 50 per page, newest first, when the client sends none
     * @return the requested page, empty when the account has no matching transactions
     */
    @GetMapping("/{accountId}/transactions")
    public ResponseEntity<Page<TransactionEntity>> getTransactionHistory(
            @PathVariable Long accountId,
            @RequestParam(required = false) TransactionType type,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Long userId = extractUserIdFromAuth();

        Page<TransactionEntity> transactions = accountService.getAccountTransactions(userId, accountId, type, pageable);

        return ResponseEntity.ok(transactions);
    }

    /**
     * Pages transaction history across every account the caller owns.
     *
     * <p>Powers the History page. The account set is resolved from the caller's own accounts, so
     * {@code accountId} can only narrow that set — it can never be used to reach an account the
     * caller does not own.
     *
     * @param accountId optional; omit to span all of the caller's accounts, or pass one they own to
     *     restrict to it — an unowned id is a 403
     * @param type optional; omit for both directions
     * @param from optional inclusive lower bound, ISO-8601 date-time
     * @param to optional inclusive upper bound, ISO-8601 date-time
     * @param pageable defaults to 50 per page, newest first, when the client sends none
     * @return the requested page; empty when the caller has no open accounts at all
     */
    @GetMapping("/transactions")
    public ResponseEntity<Page<TransactionEntity>> getAllTransactionsAcrossAccounts(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Long userId = extractUserIdFromAuth();

        Page<TransactionEntity> transactions = accountService.getAllTransactions(userId, accountId, type, from, to, pageable);

        return ResponseEntity.ok(transactions);
    }

    /**
     * Body of a self-service deposit request.
     *
     * @param amount required and strictly positive; the per-call ceiling is enforced further in,
     *     not by this record
     */
    public record DepositRequest(@NotNull @Positive BigDecimal amount) {}

    /**
     * Credits the caller's own account through the Add Funds action.
     *
     * <p>The authenticated, ownership-checked, capped counterpart to the unauthenticated
     * {@code /internal/accounts/{id}/credit} endpoint, which exists for other services and applies
     * none of those three checks. Requires KYC to be {@code APPROVED}: a 403 comes back when it is
     * not, a 503 when it could not be checked.
     *
     * @param accountId must be owned by the caller
     * @param request amount must be positive and within the service's deposit ceiling; an amount
     *     above it is a 400, not a partial deposit
     * @return the account with its new balance
     */
    @PostMapping("/{accountId}/deposit")
    public ResponseEntity<AccountOverviewResponseDto> depositFunds(
            @PathVariable Long accountId,
            @Valid @RequestBody DepositRequest request) {
        Long userId = extractUserIdFromAuth();

        AccountOverviewResponseDto updated = accountService.depositFunds(userId, accountId, request.amount());

        return ResponseEntity.ok(updated);
    }

    /**
     * Fabricates a realistic transaction history on the caller's account.
     *
     * <p>Demo affordance only. Answers 404 — deliberately, rather than 403 or 501 — whenever
     * {@code app.demo.enabled} is false, so the endpoint is indistinguishable from one that does
     * not exist in production. This is the only place that flag is checked; the service method
     * behind it does not re-check it.
     *
     * <p>Requires KYC to be {@code APPROVED} like the deposit path, because the fabricated history
     * includes credits and genuinely moves the balance.
     *
     * @param accountId must be owned by the caller
     * @return the account with the balance implied by the seeded rows, or an empty 404 body when
     *     demo mode is off
     */
    @PostMapping("/{accountId}/demo-transactions")
    public ResponseEntity<AccountOverviewResponseDto> seedDemoTransactions(@PathVariable Long accountId) {
        if (!demoModeEnabled) {
            return ResponseEntity.notFound().build();
        }

        Long userId = extractUserIdFromAuth();

        AccountOverviewResponseDto updated = accountService.seedDemoTransactions(userId, accountId);

        return ResponseEntity.ok(updated);
    }

    private Long extractUserIdFromAuth() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new SecurityException("User is not authenticated");
        }
        if (!authentication.isAuthenticated()) {
            throw new SecurityException("User is not authenticated");
        }
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getClaim("userId");
    }
}
