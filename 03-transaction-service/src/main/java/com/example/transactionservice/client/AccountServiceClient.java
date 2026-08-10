package com.example.transactionservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;

@FeignClient(name = "account-service", url = "${application.client.account-service.url:http://localhost:8083}")
public interface AccountServiceClient {

    record TransferRequest(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount) {}
    record DebitRequest(Long userId, BigDecimal amount, String description) {}
    record CreditRequest(BigDecimal amount, String description) {}
    record AccountLookupResponse(Long accountId, Long userId, String accountType, String status) {}

    @PostMapping("/api/v1/internal/accounts/transfer")
    void transfer(@RequestBody TransferRequest request);

    @PostMapping("/api/v1/internal/accounts/{accountId}/debit")
    void debit(@PathVariable("accountId") Long accountId, @RequestBody DebitRequest request);

    @PostMapping("/api/v1/internal/accounts/{accountId}/credit")
    void credit(@PathVariable("accountId") Long accountId, @RequestBody CreditRequest request);

    // FeignErrorConfig's ErrorDecoder turns a 404 here into a ResponseStatusException - callers
    // catch that specifically to distinguish "no account with this IBAN" from a real failure.
    @GetMapping("/api/v1/internal/accounts/lookup")
    AccountLookupResponse lookupByIban(@RequestParam("iban") String iban);

    // GET /api/v1/transfers (History) uses this to resolve which account IDs actually belong to
    // the caller, rather than trusting client-supplied account IDs for something
    // authorization-sensitive.
    @GetMapping("/api/v1/internal/accounts/by-user/{userId}")
    List<Long> getAccountIdsByUser(@PathVariable("userId") Long userId);
}
