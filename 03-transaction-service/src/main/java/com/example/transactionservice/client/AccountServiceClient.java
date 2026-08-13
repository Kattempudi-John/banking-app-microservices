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

    // idempotencyKey is optional on all three money-moving requests: account-service applies the
    // same key at most once and still answers success on the repeat, so a call that we cannot tell
    // succeeded (the response was lost, the local commit after it failed, an operator re-ran a
    // resolution) can be repeated without moving the money twice. A null key behaves exactly as it
    // always has, which is why each record keeps a shorter constructor - the callers that have no
    // stable key to derive shouldn't have to write an explicit null, and the ones that do are the
    // ones worth reading closely.
    record TransferRequest(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount,
                           String idempotencyKey) {
        public TransferRequest(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount) {
            this(userId, fromAccountId, toAccountId, amount, null);
        }
    }

    record DebitRequest(Long userId, BigDecimal amount, String description, String idempotencyKey) {
        public DebitRequest(Long userId, BigDecimal amount, String description) {
            this(userId, amount, description, null);
        }
    }

    record CreditRequest(BigDecimal amount, String description, String idempotencyKey) {
        public CreditRequest(BigDecimal amount, String description) {
            this(amount, description, null);
        }
    }

    // swiftCode is the BIC of the bank that actually holds this IBAN, answered by the service that
    // owns account identity. ExternalWireService compares the sender's submitted BIC against it -
    // see the mismatch check there for why format validation alone cannot catch a wrong one.
    record AccountLookupResponse(Long accountId, Long userId, String accountType, String status,
                                 String swiftCode) {}

    record RecipientLookupResponse(Long accountId, Long ownerUserId, String accountType,
                                   String maskedAccountNumber, String status) {}

    record AccountOwnerResponse(Long ownerUserId) {}

    @PostMapping("/api/v1/internal/accounts/transfer")
    void transfer(@RequestBody TransferRequest request);

    // Same payload as transfer() above, but account-service only ownership-checks the source side -
    // the destination is expected to belong to someone else.
    @PostMapping("/api/v1/internal/accounts/transfer-to-recipient")
    void transferToRecipient(@RequestBody TransferRequest request);

    // Turns a full account number the sender typed into the internal account id needed to credit it.
    // 404s through the same ErrorDecoder path as lookupByIban when no such account exists.
    @GetMapping("/api/v1/internal/accounts/by-number/{accountNumber}")
    RecipientLookupResponse lookupByAccountNumber(@PathVariable("accountNumber") String accountNumber);

    // Answers who owns an account we already hold the internal id for - the reverse direction of the
    // two lookups above, which start from something the sender typed. Needed on the fraud-review
    // credit path, where the only thing recorded about the destination is its account id and the
    // recipient still has to be re-vetted before the money lands. 404s through the same ErrorDecoder
    // path as the lookups above when the account no longer exists.
    @GetMapping("/api/v1/internal/accounts/{accountId}/owner")
    AccountOwnerResponse lookupAccountOwner(@PathVariable("accountId") Long accountId);

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
