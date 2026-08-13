package com.example.notificationservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;
import java.util.List;

// second feign client in this service, same declarative pattern as ProfileServiceClient below,
// just pointed at a different downstream service and url property
@FeignClient(name = "account-service", url = "${account-service.url:http://localhost:8083}")
public interface AccountServiceClient {

    record UserAggregateBalanceResponse(
            Long userId,
            BigDecimal totalBalance
    ) {}

    record AccountOwnerResponse(Long ownerUserId) {}

    // Moved under /api/v1/internal/ so the k8s ingress stops routing it publicly - it answers with
    // users' total balances and is unauthenticated by necessity, since there's no end-user token on a
    // service-to-service call.
    @PostMapping("/api/v1/internal/accounts/balances/batch")
    List<UserAggregateBalanceResponse> getAggregateBalancesBatch(@RequestBody List<Long> userIds);

    // "Whose account is this?" - the first half of naming the counterparty on a transaction alert
    // (the second half is AuthServiceClient.getDisplayName). The transfer event carries account IDs
    // only, and an account ID belongs to a different ID sequence than a user ID, so it cannot be
    // used as one. Same endpoint transaction-service already calls for its recipient checks.
    //
    // It answers with the owner and nothing else, which is also what makes the own-transfer case
    // detectable: an owner equal to the alerted user means money moved between that user's own two
    // accounts, not out to a stranger.
    @GetMapping("/api/v1/internal/accounts/{accountId}/owner")
    AccountOwnerResponse getAccountOwner(@PathVariable("accountId") Long accountId);
}