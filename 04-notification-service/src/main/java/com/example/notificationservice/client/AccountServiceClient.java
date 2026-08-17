package com.example.notificationservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;
import java.util.List;

/**
 * Reads balances and account ownership from account-service.
 *
 * <p>A declarative Feign client — the implementation is generated at runtime, unlike
 * {@link EmailProviderClient} and {@link SmsProviderClient} in the same package, which are plain
 * interfaces with hand-written implementations. The base URL comes from
 * {@code account-service.url}, defaulting to {@code http://localhost:8083} for local runs.
 *
 * <p>Both endpoints live under {@code /api/v1/internal/} so the Kubernetes ingress does not route
 * them publicly. They are unauthenticated by necessity — a service-to-service call carries no
 * end-user token — and one of them answers with users' total balances, so that path prefix is the
 * only thing keeping them off the public internet.
 */
@FeignClient(name = "account-service", url = "${account-service.url:http://localhost:8083}")
public interface AccountServiceClient {

    record UserAggregateBalanceResponse(
            Long userId,
            BigDecimal totalBalance
    ) {}

    record AccountOwnerResponse(Long ownerUserId) {}

    /**
     * Fetches the total balance across all accounts for each of the given users, in one call.
     *
     * @param userIds the users to total; sent as a request body rather than a query string because
     *     the daily-summary sweep passes the whole opted-in population
     * @return one entry per user account-service could resolve, so the result may be shorter than
     *     the input and is not ordered to match it
     */
    @PostMapping("/api/v1/internal/accounts/balances/batch")
    List<UserAggregateBalanceResponse> getAggregateBalancesBatch(@RequestBody List<Long> userIds);

    /**
     * Resolves which user owns an account.
     *
     * <p>This is the first half of naming the counterparty on a transaction alert; the second half
     * is {@link AuthServiceClient#getDisplayName}. It is needed because a transfer event carries
     * account IDs only, and account IDs come from a different sequence than user IDs, so one can
     * never be used as the other.
     *
     * <p>The owner is also what makes a self-transfer detectable: an owner equal to the alerted user
     * means money moved between two of that user's own accounts rather than out to a stranger, which
     * callers use to suppress or reword the alert.
     *
     * @param accountId an existing account ID; an unknown one is a 404 from account-service, which
     *     Feign surfaces as an exception rather than a {@code null} result
     * @return the owning user, carrying no other account detail
     */
    @GetMapping("/api/v1/internal/accounts/{accountId}/owner")
    AccountOwnerResponse getAccountOwner(@PathVariable("accountId") Long accountId);
}