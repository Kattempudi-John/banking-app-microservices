package com.example.accountservice;

import com.example.accountservice.client.ProfileServiceClient;
import com.example.accountservice.dto.AccountOverviewResponseDto;
import com.example.accountservice.mapper.AccountMapper;
import com.example.accountservice.model.AccountEntity;
import com.example.accountservice.model.AccountStatus;
import com.example.accountservice.model.AccountType;
import com.example.accountservice.model.TransactionEntity;
import com.example.accountservice.model.TransactionType;
import com.example.accountservice.repository.AccountRepository;
import com.example.accountservice.repository.TransactionRepository;
import com.example.accountservice.service.AccountService;
import com.example.accountservice.service.UserRegisteredListener;
import com.example.accountservice.util.IbanGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AccountServiceTestSuite {

    // The shared secret guarding /api/v1/internal/**, identical in all five services. The literal is
    // the dev default from application.yml, which is what this context resolves the property to.
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String INTERNAL_TOKEN = "local-dev-internal-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private UserRegisteredListener userRegisteredListener;

    @Autowired
    private IbanGenerator ibanGenerator;

    @MockBean
    private AccountRepository accountRepository;

    @MockBean
    private TransactionRepository transactionRepository;

    // KycEnforcementAspect calls out to profile-service before any deposit or account opening.
    // Mocked so these tests decide the caller's KYC status instead of needing that service running.
    @MockBean
    private ProfileServiceClient profileServiceClient;

    private AccountEntity activeChecking;

    // shared fixture built before every test, one active checking account belonging to user 42
    // giving it a real routing number and account number so the masking logic has something real to mask
    // status starts out active since most tests below care about the normal, non closed case
    @BeforeEach
    void setUp() {
        activeChecking = new AccountEntity();
        activeChecking.setId(1L);
        activeChecking.setUserId(42L);
        activeChecking.setAccountType(AccountType.CHECKING);
        activeChecking.setAvailableBalance(new BigDecimal("1500.0000"));
        activeChecking.setRoutingNumber("021000021");
        activeChecking.setAccountNumber("9876543210");
        activeChecking.setStatus(AccountStatus.ACTIVE);
    }

    // mocks the same Jwt-shaped principal the real oauth2 resource server filter builds in
    // production - the "scope" claim drives the SCOPE_FULL_AUTH authority via the default
    // JwtGrantedAuthoritiesConverter, and "userId" is what the controllers actually read
    private static RequestPostProcessor fullAuthUser(long userId) {
        return jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", userId));
    }

    // same idea but with a Pre-Auth scope, simulating a session where 2FA was never completed
    private static RequestPostProcessor preAuthUser(long userId) {
        return jwt().jwt(j -> j.claim("scope", "PRE_AUTH").claim("userId", userId));
    }

    // checking the dashboard endpoint filters out closed accounts and masks the raw account number
    // simulates user 42 logged in with a full auth session
    // stub the repository so this user's non closed accounts come back as just the one fixture account
    // hit get /api/v1/accounts
    // expect just one account in the response, checking type, masked number showing only last four digits,
    // and the routing number coming through in full since that one is not sensitive the same way
    @Test
    @DisplayName("Block 1: Dashboard excludes CLOSED accounts, masks account number - [MEANT TO PASS]")
    void testBlock1_dashboardExcludesClosedAccountsAndMasksNumber() throws Exception {
        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED))
                .willReturn(List.of(activeChecking));

        mockMvc.perform(get("/api/v1/accounts").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].accountType").value("CHECKING"))
                .andExpect(jsonPath("$[0].maskedAccountNumber").value("......3210"))
                // The unmasked number rides along beside the masked one on this endpoint only. The
                // dashboard shows the masked form, but the Receive Money panel has to be able to
                // hand the owner their whole number - paying another user goes by account number,
                // so a user who cannot read their own can never be paid.
                .andExpect(jsonPath("$[0].accountNumber").value("9876543210"))
                .andExpect(jsonPath("$[0].routingNumber").value("021000021"));
    }

    // making sure a user with zero active accounts gets back an empty list, not some kind of error
    // stub the repository so this user's query returns an empty list
    // hit the dashboard endpoint
    // expect a normal 200 ok with a zero length array, an empty account list is a valid state, not a bug
    @Test
    @DisplayName("Block 2: Dashboard returns empty array (not an error) when user has no active accounts - [MEANT TO PASS]")
    void testBlock2_emptyDashboardWhenNoActiveAccounts() throws Exception {
        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/accounts").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // security focused test, making sure the endpoint trusts the jwt and not a query param for user id
    // stub the repository so the real logged in user, 42, has no accounts
    // hit the dashboard but pass a spoofed userId=999 query param, trying to impersonate another user
    // that param should be completely ignored, the repo call still only ever used the real jwt user id
    // so the response should reflect user 42's data, which is empty here, not user 999's
    @Test
    @DisplayName("Block 3: userId is extracted from the JWT/SecurityContext, not a spoofable request param - [MEANT TO PASS]")
    void testBlock3_userIdExtractedFromSecurityContextNotParams() throws Exception {
        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED)).willReturn(List.of());

        // A spoofed userId query param must be ignored; the repository is still queried with 42 (the JWT principal)
        mockMvc.perform(get("/api/v1/accounts?userId=999").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // making sure a pre auth token, meaning 2fa was never finished, cannot reach the dashboard
    // only grants scope_pre_auth instead of the full auth scope the other tests use
    // hit the dashboard endpoint with that partial authority
    // expect a 403 forbidden, since the class level security check requires full auth specifically
    @Test
    @DisplayName("Block 4: Pre-Auth JWT (2FA incomplete) is rejected with 403 on the dashboard - [MEANT TO FAIL]")
    void testBlock4_preAuthTokenRejectedOnDashboard() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").with(preAuthUser(42)))
                .andExpect(status().isForbidden());
    }

    // similar idea to the last test but this time there is no logged in user at all
    // no auth request post processor on this one on purpose
    // hit the dashboard endpoint completely unauthenticated
    // expect some flavor of 4xx client error, confirming anonymous requests never reach real account data
    @Test
    @DisplayName("Block 5: Unauthenticated request is rejected on the dashboard - [MEANT TO FAIL]")
    void testBlock5_unauthenticatedRequestRejectedOnDashboard() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().is4xxClientError());
    }

    // end to end style check that pulls together masking, filtering and correct balance formatting
    // gives a proper full auth session for user 42
    // stub the repository to return the one active checking account fixture
    // hit the dashboard endpoint
    // expect the masked number to only show the last four digits, the balance to come through as
    // a real number not a string, the routing number in full, and status reported as active
    @Test
    @DisplayName("Final Block: Full-Auth user retrieves masked, filtered, correctly-priced dashboard - [MEANT TO PASS]")
    void testFinalAC_fullAuthUserGetsMaskedFilteredDashboard() throws Exception {
        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED))
                .willReturn(List.of(activeChecking));

        mockMvc.perform(get("/api/v1/accounts").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].maskedAccountNumber").value("......3210"))
                .andExpect(jsonPath("$[0].availableBalance").value(1500.0))
                .andExpect(jsonPath("$[0].routingNumber").value("021000021"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    // checking that hitting the transactions endpoint with no page params still applies sane defaults
    // stub the account lookup so account 1 resolves to our fixture, owned by user 42
    // stub the transaction repository for the exact pageable spring should build internally,
    // page zero, size fifty, sorted newest first by createdAt, since a generic pageimpl with an
    // empty list would otherwise report size zero regardless of what was actually asked for
    // hit get /api/v1/accounts/1/transactions with no query params at all
    // expect status ok and the size field in the response to reflect the real default of fifty
    @Test
    @DisplayName("Block 6: Default pagination applies size=50 and DESC sort by createdAt - [MEANT TO PASS]")
    void testBlock6_defaultPaginationAppliedCorrectly() throws Exception {
        given(accountRepository.findById(1L)).willReturn(Optional.of(activeChecking));
        // Stub with the exact expected Pageable so the page metadata (size=50) reflects the real request,
        // not an unpaged default - PageImpl<>(List.of()) alone reports size=0 regardless of what was asked.
        given(transactionRepository.findByAccountId(eq(1L), eq(PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")))))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")), 0));

        mockMvc.perform(get("/api/v1/accounts/1/transactions").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50));
    }

    // making sure passing type=debit actually routes to the filtered repository query, not the general one
    // build one debit transaction using the little buildTransaction helper at the bottom of the file
    // stub the account lookup, then stub findbyaccountidandtransactiontype specifically for debit
    // hit the endpoint with the type=DEBIT query param
    // expect one transaction back in the content array, and that its type is debit
    @Test
    @DisplayName("Block 7: type=DEBIT routes to the filtered repository query - [MEANT TO PASS]")
    void testBlock7_debitFilterUsesFilteredQuery() throws Exception {
        TransactionEntity debit = buildTransaction(1L, TransactionType.DEBIT, new BigDecimal("75.5000"));
        given(accountRepository.findById(1L)).willReturn(Optional.of(activeChecking));
        given(transactionRepository.findByAccountIdAndTransactionType(eq(1L), eq(TransactionType.DEBIT), any()))
                .willReturn(new PageImpl<>(List.of(debit)));

        mockMvc.perform(get("/api/v1/accounts/1/transactions").param("type", "DEBIT").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].transactionType").value("DEBIT"));
    }

    // the flip side of the last test, no type filter should return everything, credits and debits both
    // build one credit and one debit transaction with the helper
    // stub the account lookup, then stub the plain findbyaccountid query, the unfiltered version
    // hit the endpoint with no type param at all
    // expect both transactions back in the content array
    @Test
    @DisplayName("Block 8: No type filter returns all transactions via the unfiltered query - [MEANT TO PASS]")
    void testBlock8_noFilterReturnsAllTransactions() throws Exception {
        TransactionEntity credit = buildTransaction(1L, TransactionType.CREDIT, new BigDecimal("200.0000"));
        TransactionEntity debit = buildTransaction(1L, TransactionType.DEBIT, new BigDecimal("50.0000"));
        given(accountRepository.findById(1L)).willReturn(Optional.of(activeChecking));
        given(transactionRepository.findByAccountId(eq(1L), any()))
                .willReturn(new PageImpl<>(List.of(credit, debit)));

        mockMvc.perform(get("/api/v1/accounts/1/transactions").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    // security check for ownership, user 42 should not be able to read another user's account transactions
    // build an account entity by hand that belongs to a completely different user, 999
    // stub the account lookup so account id 1 resolves to that not owned account
    // hit /api/v1/accounts/1/transactions logged in as user 42
    // expect a 403 forbidden, confirming the ownership check runs before any transaction data leaks
    @Test
    @DisplayName("Block 9: Requesting another user's accountId is rejected with 403 - [MEANT TO FAIL]")
    void testBlock9_ownershipMismatchReturns403() throws Exception {
        AccountEntity notOwned = new AccountEntity();
        notOwned.setId(1L);
        notOwned.setUserId(999L);
        given(accountRepository.findById(1L)).willReturn(Optional.of(notOwned));

        mockMvc.perform(get("/api/v1/accounts/1/transactions").with(fullAuthUser(42)))
                .andExpect(status().isForbidden());
    }

    // making sure asking for an account id that does not exist gives a clean 404, not a crash
    // stub the account lookup for id 999 so it returns completely empty
    // hit /api/v1/accounts/999/transactions
    // accountservice now throws a responsestatusexception with not_found for this case,
    // so spring maps it to a real http status instead of letting it blow up as an unhandled 500
    // expect some flavor of 4xx client error back
    @Test
    @DisplayName("Block 10: Non-existent accountId returns 404, not an unhandled 500 - [MEANT TO FAIL]")
    void testBlock10_nonExistentAccountIdReturns404() throws Exception {
        // AccountService.getAccountTransactions() now throws ResponseStatusException(NOT_FOUND, ...)
        // for a missing account instead of a raw IllegalArgumentException, so Spring MVC maps it to
        // a proper 404 rather than letting it escape as an unhandled 500.
        given(accountRepository.findById(999L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/accounts/999/transactions").with(fullAuthUser(42)))
                .andExpect(status().is4xxClientError());
    }

    // same pre auth restriction as the dashboard test earlier, but this time on the transactions endpoint
    // only grants scope_pre_auth here, meaning 2fa was never completed
    // hit the transactions endpoint for account 1 with that partial authority
    // expect a 403 forbidden since the class level preauthorize check covers every endpoint in this controller
    @Test
    @DisplayName("Block 11: Pre-Auth JWT cannot access transaction history - [MEANT TO FAIL]")
    void testBlock11_preAuthTokenBlockedFromTransactionHistory() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/1/transactions").with(preAuthUser(42)))
                .andExpect(status().isForbidden());
    }

    // full end to end test tying pagination, filtering and ownership together in one request
    // build one credit transaction with the helper method
    // stub the account lookup so account 1 resolves to the fixture, owned by the logged in user
    // stub the filtered repository query for exactly page zero, size fifty, sorted by createdAt desc, credit only
    // hit the endpoint with explicit page, size and type query params matching that stub
    // expect status ok, the one credit transaction in the content array, and the page metadata
    // (total elements, total pages, current page number) all reflecting a single result correctly
    @Test
    @DisplayName("Final Block: Owner retrieves paginated, filtered, well-formed transaction page - [MEANT TO PASS]")
    void testFinalAC_ownerRetrievesPaginatedFilteredHistory() throws Exception {
        TransactionEntity credit = buildTransaction(1L, TransactionType.CREDIT, new BigDecimal("999.9900"));
        given(accountRepository.findById(1L)).willReturn(Optional.of(activeChecking));
        given(transactionRepository.findByAccountIdAndTransactionType(
                eq(1L), eq(TransactionType.CREDIT), eq(PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")))))
                .willReturn(new PageImpl<>(List.of(credit), PageRequest.of(0, 50), 1));

        mockMvc.perform(get("/api/v1/accounts/1/transactions?page=0&size=50&type=CREDIT").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionType").value("CREDIT"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0));
    }

    // ==========================================
    // UserRegisteredListener (provisions a starter account for a brand-new auth-service user)
    // ==========================================

    @Test
    @DisplayName("UserRegistered event creates a $0 ACTIVE CHECKING account for a new user id - [MEANT TO PASS]")
    void testUserRegisteredListener_NewUser_CreatesStarterCheckingAccount() {
        given(accountRepository.existsByUserId(777L)).willReturn(false);
        Map<String, Object> event = Map.of("userId", "777", "username", "newuser", "phoneNumber", "+15550001111");

        userRegisteredListener.consumeUserRegistered(event);

        verify(accountRepository).save(argThat(account ->
                account.getUserId().equals(777L)
                        && account.getAccountType() == AccountType.CHECKING
                        && account.getAvailableBalance().compareTo(BigDecimal.ZERO) == 0
                        && account.getStatus() == AccountStatus.ACTIVE
                        && account.getRoutingNumber() != null
                        && account.getAccountNumber() != null
        ));
    }

    @Test
    @DisplayName("UserRegistered event is a no-op if the user already has an account - [MEANT TO PASS]")
    void testUserRegisteredListener_ExistingUser_DoesNotCreateDuplicateAccount() {
        given(accountRepository.existsByUserId(777L)).willReturn(true);
        Map<String, Object> event = Map.of("userId", "777", "username", "newuser", "phoneNumber", "+15550001111");

        userRegisteredListener.consumeUserRegistered(event);

        verify(accountRepository, never()).save(any(AccountEntity.class));
    }

    // ==========================================
    // IbanGenerator / on-network transfer support (see transaction-service's ExternalWireService,
    // which resolves an incoming wire's IBAN against this same accounts table)
    // ==========================================

    @Test
    @DisplayName("UserRegistered event sets a checksum-valid IBAN built from the routing + account number - [MEANT TO PASS]")
    void testUserRegisteredListener_NewUser_GeneratesValidIban() {
        given(accountRepository.existsByUserId(777L)).willReturn(false);
        Map<String, Object> event = Map.of("userId", "777", "username", "newuser", "phoneNumber", "+15550001111");

        userRegisteredListener.consumeUserRegistered(event);

        verify(accountRepository).save(argThat(account ->
                account.getIban() != null
                        && account.getIban().endsWith(account.getRoutingNumber() + account.getAccountNumber())
                        && hasValidIbanChecksum(account.getIban())
        ));
    }

    @Test
    @DisplayName("IbanGenerator produces a different IBAN for a different account number - [MEANT TO PASS]")
    void testIbanGenerator_differentAccountNumbersProduceDifferentIbans() {
        String ibanOne = ibanGenerator.generate("021000021", "100000000001");
        String ibanTwo = ibanGenerator.generate("021000021", "100000000002");

        assertThat(ibanOne).isNotEqualTo(ibanTwo);
        assertThat(hasValidIbanChecksum(ibanOne)).isTrue();
        assertThat(hasValidIbanChecksum(ibanTwo)).isTrue();
    }

    @Test
    @DisplayName("Internal IBAN lookup returns the matching account when one exists - [MEANT TO PASS]")
    void testInternalLookupByIban_MatchFound() throws Exception {
        activeChecking.setIban("XB00021000021123456789012");
        given(accountRepository.findByIban("XB00021000021123456789012")).willReturn(Optional.of(activeChecking));

        mockMvc.perform(get("/api/v1/internal/accounts/lookup").param("iban", "XB00021000021123456789012")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(1))
                .andExpect(jsonPath("$.userId").value(42))
                // transaction-service refuses a wire whose BIC doesn't match this, so the field has
                // to actually be populated - a null here would silently fail that check closed and
                // start rejecting every legitimate on-us wire.
                .andExpect(jsonPath("$.swiftCode").value("XBUSUS31"));
    }

    @Test
    @DisplayName("Internal IBAN lookup returns 404 when no account on this platform matches - [MEANT TO FAIL]")
    void testInternalLookupByIban_NoMatch() throws Exception {
        given(accountRepository.findByIban("XB00000000000000000000000")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/internal/accounts/lookup").param("iban", "XB00000000000000000000000")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isNotFound());
    }

    // The counterpart to Block 1's accountNumber assertion, and the reason the two responses are
    // separate records rather than one shared DTO. GET /api/v1/accounts is scoped to the caller's own
    // accounts, so returning the raw number there is fine. This lookup describes SOMEBODY ELSE's
    // account - a sender confirming who they're about to pay - so it must never echo the full number
    // back, only the masked confirmation the sender already typed.
    @Test
    @DisplayName("Recipient lookup masks the number and never exposes the raw one - [MEANT TO PASS]")
    void testInternalLookupByAccountNumber_NeverExposesRawNumber() throws Exception {
        given(accountRepository.findByAccountNumber("9876543210")).willReturn(Optional.of(activeChecking));

        mockMvc.perform(get("/api/v1/internal/accounts/by-number/9876543210").header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedAccountNumber").value("......3210"))
                .andExpect(jsonPath("$.accountNumber").doesNotExist());
    }

    // ==========================================
    // Cross-account History (GET /api/v1/accounts/transactions, GET /api/v1/internal/accounts/by-user/{userId})
    // ==========================================

    @Test
    @DisplayName("Cross-account history aggregates transactions across every account the caller owns - [MEANT TO PASS]")
    void testGetAllTransactions_AggregatesAcrossOwnedAccounts() throws Exception {
        AccountEntity secondAccount = new AccountEntity();
        secondAccount.setId(2L);
        secondAccount.setUserId(42L);
        secondAccount.setAccountType(AccountType.SAVINGS);
        secondAccount.setStatus(AccountStatus.ACTIVE);

        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED))
                .willReturn(List.of(activeChecking, secondAccount));
        given(transactionRepository.findByAccountIdInWithFilters(eq(List.of(1L, 2L)), isNull(), isNull(), isNull(), any()))
                .willReturn(new PageImpl<>(List.of(buildTransaction(1L, TransactionType.CREDIT, new BigDecimal("50.0000")))));

        mockMvc.perform(get("/api/v1/accounts/transactions").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    @DisplayName("Cross-account history narrowed to an accountId not owned by the caller is forbidden - [MEANT TO FAIL]")
    void testGetAllTransactions_AccountIdFilterNotOwned_Returns403() throws Exception {
        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED))
                .willReturn(List.of(activeChecking));

        mockMvc.perform(get("/api/v1/accounts/transactions").param("accountId", "999").with(fullAuthUser(42)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Internal by-user endpoint returns the caller's account IDs - [MEANT TO PASS]")
    void testInternalAccountsByUser_ReturnsAccountIds() throws Exception {
        AccountEntity secondAccount = new AccountEntity();
        secondAccount.setId(2L);
        secondAccount.setUserId(42L);
        secondAccount.setAccountType(AccountType.SAVINGS);
        secondAccount.setStatus(AccountStatus.ACTIVE);

        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED))
                .willReturn(List.of(activeChecking, secondAccount));

        mockMvc.perform(get("/api/v1/internal/accounts/by-user/42").header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value(1))
                .andExpect(jsonPath("$[1]").value(2));
    }

    // ==========================================
    // KYC gate on funding and account opening (POST /api/v1/accounts, POST /{id}/deposit)
    // ==========================================

    // An unverified identity putting money into the bank is the exact thing know-your-customer
    // rules exist to stop, so this is refused the same way a transfer already is.
    @Test
    @DisplayName("KYC gate: Deposit is refused with 403 while KYC is PENDING_VERIFICATION - [MEANT TO FAIL]")
    void testDeposit_PendingKyc_Rejected() throws Exception {
        given(profileServiceClient.getKycStatus(42L)).willReturn(Map.of("status", "PENDING_VERIFICATION"));

        mockMvc.perform(post("/api/v1/accounts/1/deposit")
                .with(fullAuthUser(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100.00}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("PENDING_VERIFICATION")));

        // The balance must be untouched - a rejected deposit that still wrote the credit would be
        // worse than no gate at all.
        verify(accountRepository, never()).save(any(AccountEntity.class));
        verify(transactionRepository, never()).save(any(TransactionEntity.class));
    }

    @Test
    @DisplayName("KYC gate: Deposit succeeds once KYC is APPROVED - [MEANT TO PASS]")
    void testDeposit_ApprovedKyc_Succeeds() throws Exception {
        given(profileServiceClient.getKycStatus(42L)).willReturn(Map.of("status", "APPROVED"));
        given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeChecking));

        mockMvc.perform(post("/api/v1/accounts/1/deposit")
                .with(fullAuthUser(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100.00}"))
                .andExpect(status().isOk());

        assertThat(activeChecking.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("1600.00"));
        verify(transactionRepository).save(any(TransactionEntity.class));
    }

    // Same gate on the other direction: opening an account is the classic KYC moment.
    @Test
    @DisplayName("KYC gate: Opening an account is refused with 403 while KYC is REJECTED - [MEANT TO FAIL]")
    void testOpenAccount_RejectedKyc_Rejected() throws Exception {
        given(profileServiceClient.getKycStatus(42L)).willReturn(Map.of("status", "REJECTED"));

        mockMvc.perform(post("/api/v1/accounts")
                .with(fullAuthUser(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountType\":\"SAVINGS\"}"))
                .andExpect(status().isForbidden());

        verify(accountRepository, never()).save(any(AccountEntity.class));
    }

    @Test
    @DisplayName("KYC gate: Opening an account succeeds once KYC is APPROVED - [MEANT TO PASS]")
    void testOpenAccount_ApprovedKyc_Succeeds() throws Exception {
        given(profileServiceClient.getKycStatus(42L)).willReturn(Map.of("status", "APPROVED"));
        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED)).willReturn(List.of());

        mockMvc.perform(post("/api/v1/accounts")
                .with(fullAuthUser(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountType\":\"SAVINGS\"}"))
                .andExpect(status().isOk());

        verify(accountRepository).save(any(AccountEntity.class));
    }

    // The starter account a brand-new user receives comes from the Kafka listener, not this
    // service method, so provisioning has to keep working for a user who is still PENDING - if this
    // ever regresses, registration silently stops giving new users an account at all.
    @Test
    @DisplayName("KYC gate: Registration provisioning still works for a PENDING user - [MEANT TO PASS]")
    void testUserRegisteredProvisioning_UnaffectedByKycGate() {
        given(profileServiceClient.getKycStatus(any())).willReturn(Map.of("status", "PENDING_VERIFICATION"));
        given(accountRepository.findByUserIdAndStatusNot(99L, AccountStatus.CLOSED)).willReturn(List.of());

        userRegisteredListener.consumeUserRegistered(Map.of("userId", 99, "username", "newuser"));

        verify(accountRepository).save(any(AccountEntity.class));
    }

    // ==========================================
    // Owner lookup (GET /api/v1/internal/accounts/{accountId}/owner)
    // ==========================================

    @Test
    @DisplayName("Owner lookup resolves an account id to the user who owns it - [MEANT TO PASS]")
    void testInternalOwnerLookup_KnownAccount_ReturnsOwnerId() throws Exception {
        given(accountRepository.findById(1L)).willReturn(Optional.of(activeChecking));

        mockMvc.perform(get("/api/v1/internal/accounts/1/owner").header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId").value(42));
    }

    @Test
    @DisplayName("Owner lookup returns 404 for an account id that does not exist - [MEANT TO FAIL]")
    void testInternalOwnerLookup_UnknownAccount_Returns404() throws Exception {
        given(accountRepository.findById(999L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/internal/accounts/999/owner").header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isNotFound());
    }

    // The whole point of a dedicated single-field response instead of reusing one of the richer
    // lookup records: the caller asked who owns the account, so that is all it gets. This endpoint
    // sits under the unauthenticated /api/v1/internal prefix, so anything that leaks here leaks
    // to whatever can reach the pod, not just to a logged-in owner.
    @Test
    @DisplayName("Owner lookup exposes only the owner id, never the number, balance or IBAN - [MEANT TO PASS]")
    void testInternalOwnerLookup_DoesNotLeakAccountDetails() throws Exception {
        activeChecking.setIban("XB00021000021123456789012");
        given(accountRepository.findById(1L)).willReturn(Optional.of(activeChecking));

        mockMvc.perform(get("/api/v1/internal/accounts/1/owner").header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId").value(42))
                .andExpect(jsonPath("$.accountNumber").doesNotExist())
                .andExpect(jsonPath("$.maskedAccountNumber").doesNotExist())
                .andExpect(jsonPath("$.availableBalance").doesNotExist())
                .andExpect(jsonPath("$.iban").doesNotExist())
                .andExpect(jsonPath("$.routingNumber").doesNotExist());
    }

    // ==========================================
    // KYC gate when profile-service cannot answer (503, still fail-closed)
    // ==========================================

    // profile-service being unreachable surfaces as the Feign client throwing rather than
    // returning an empty body, which used to escape unmapped and reach the user as a 500 - reading
    // as "account-service is broken" when the gate had in fact worked exactly as intended.
    @Test
    @DisplayName("KYC gate: Deposit answers 503 (not 500) when profile-service is unreachable - [MEANT TO FAIL]")
    void testDeposit_ProfileServiceUnreachable_Returns503() throws Exception {
        given(profileServiceClient.getKycStatus(42L))
                .willThrow(new RuntimeException("connect timed out", new ConnectException("Connection refused")));

        mockMvc.perform(post("/api/v1/accounts/1/deposit")
                .with(fullAuthUser(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100.00}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists())
                // Both keys carry the same text because the frontend reads either one.
                .andExpect(jsonPath("$.message").exists());

        // The status change must not have loosened the gate: an unconfirmable identity still
        // deposits nothing. A 503 that credited the account anyway would be far worse than the 500.
        verify(accountRepository, never()).save(any(AccountEntity.class));
        verify(transactionRepository, never()).save(any(TransactionEntity.class));
    }

    // The other half of the same failure: profile-service answers, but with nothing usable in it.
    // Treated identically - an absent status is not an approval.
    @Test
    @DisplayName("KYC gate: Deposit answers 503 when profile-service returns a body with no status - [MEANT TO FAIL]")
    void testDeposit_ProfileServiceReturnsNoStatus_Returns503() throws Exception {
        given(profileServiceClient.getKycStatus(42L)).willReturn(Map.of("userId", "42"));

        mockMvc.perform(post("/api/v1/accounts/1/deposit")
                .with(fullAuthUser(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100.00}"))
                .andExpect(status().isServiceUnavailable());

        verify(accountRepository, never()).save(any(AccountEntity.class));
        verify(transactionRepository, never()).save(any(TransactionEntity.class));
    }

    // Opening an account goes through the same aspect, so it must report the same status rather
    // than falling back to the old 500 on whichever path happened not to be covered.
    @Test
    @DisplayName("KYC gate: Opening an account answers 503 when profile-service is unreachable - [MEANT TO FAIL]")
    void testOpenAccount_ProfileServiceUnreachable_Returns503() throws Exception {
        given(profileServiceClient.getKycStatus(42L))
                .willThrow(new RuntimeException("connect timed out", new ConnectException("Connection refused")));

        mockMvc.perform(post("/api/v1/accounts")
                .with(fullAuthUser(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountType\":\"SAVINGS\"}"))
                .andExpect(status().isServiceUnavailable());

        verify(accountRepository, never()).save(any(AccountEntity.class));
    }

    // Guards the distinction the 503 introduces: "we could not check" must not have swallowed
    // "we checked and you are not approved". A known-bad status is still a 403 naming that status,
    // and an approved one still goes through - see testDeposit_PendingKyc_Rejected and
    // testDeposit_ApprovedKyc_Succeeds above for the deposit side of the same pair.
    @Test
    @DisplayName("KYC gate: An unapproved status is still 403, not the new 503 - [MEANT TO FAIL]")
    void testDeposit_UnapprovedKyc_StillReturns403NotUnavailable() throws Exception {
        given(profileServiceClient.getKycStatus(42L)).willReturn(Map.of("status", "PENDING_VERIFICATION"));

        mockMvc.perform(post("/api/v1/accounts/1/deposit")
                .with(fullAuthUser(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100.00}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("PENDING_VERIFICATION")));

        verify(accountRepository, never()).save(any(AccountEntity.class));
        verify(transactionRepository, never()).save(any(TransactionEntity.class));
    }

    @Test
    @DisplayName("KYC gate: An APPROVED status still completes the deposit - [MEANT TO PASS]")
    void testDeposit_ApprovedKyc_StillSucceedsAfterUnavailableHandling() throws Exception {
        given(profileServiceClient.getKycStatus(42L)).willReturn(Map.of("status", "APPROVED"));
        given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeChecking));

        mockMvc.perform(post("/api/v1/accounts/1/deposit")
                .with(fullAuthUser(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100.00}"))
                .andExpect(status().isOk());

        assertThat(activeChecking.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("1600.00"));
        verify(transactionRepository).save(any(TransactionEntity.class));
    }

    // ==========================================
    // Shared secret on the internal surface (InternalTokenFilter, X-Internal-Token)
    // ==========================================

    // The endpoint this whole layer exists for. Before the filter, anything that could open a socket
    // to this service's port could add any amount to any account with no credential whatsoever.
    @Test
    @DisplayName("Internal token: credit with no X-Internal-Token is refused 401 and touches nothing - [MEANT TO FAIL]")
    void testInternalCredit_MissingToken_Rejected() throws Exception {
        mockMvc.perform(post("/api/v1/internal/accounts/1/credit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100.00,\"description\":\"Incoming wire\"}"))
                .andExpect(status().isUnauthorized())
                // Both keys carry the same text, matching every other error body in this project.
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());

        // Stronger than "the balance did not change": the rejection must happen in the filter, before
        // the controller is ever reached, so an unauthenticated caller cannot even probe which account
        // ids exist by timing or by the shape of the error it gets back.
        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Internal token: credit with the wrong X-Internal-Token is refused 401 - [MEANT TO FAIL]")
    void testInternalCredit_WrongToken_Rejected() throws Exception {
        mockMvc.perform(post("/api/v1/internal/accounts/1/credit")
                .header(INTERNAL_TOKEN_HEADER, "not-the-real-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100.00,\"description\":\"Incoming wire\"}"))
                .andExpect(status().isUnauthorized())
                // The failure must not describe the mechanism being probed - naming the header or the
                // property in the body would tell an attacker exactly what they are missing, and
                // distinguishing "wrong" from "missing" would confirm when a guess is well-formed.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(INTERNAL_TOKEN_HEADER))))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal-token"))));

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Internal token: credit with the correct X-Internal-Token behaves exactly as before - [MEANT TO PASS]")
    void testInternalCredit_CorrectToken_Succeeds() throws Exception {
        given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeChecking));

        mockMvc.perform(post("/api/v1/internal/accounts/1/credit")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100.00,\"description\":\"Incoming wire\"}"))
                .andExpect(status().isOk());

        assertThat(activeChecking.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("1600.00"));
        verify(transactionRepository).save(any(TransactionEntity.class));
    }

    @Test
    @DisplayName("Internal token: debit with no X-Internal-Token is refused 401 and touches nothing - [MEANT TO FAIL]")
    void testInternalDebit_MissingToken_Rejected() throws Exception {
        mockMvc.perform(post("/api/v1/internal/accounts/1/debit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":42,\"amount\":100.00,\"description\":\"Outgoing wire\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionRepository);
    }

    // The scoping half of the contract, and the reason the filter checks the path prefix at all: the
    // secret guards service-to-service traffic only. If it ever leaked onto the customer-facing API,
    // every logged-in browser would have to hold a server credential to see its own dashboard.
    @Test
    @DisplayName("Internal token: the customer-facing JWT API still works with no X-Internal-Token - [MEANT TO PASS]")
    void testCustomerFacingEndpoint_NoInternalToken_StillWorks() throws Exception {
        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED))
                .willReturn(List.of(activeChecking));

        mockMvc.perform(get("/api/v1/accounts").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ==========================================
    // Idempotent ledger writes (optional idempotencyKey on credit/debit/transfer)
    // ==========================================

    // transaction-service issues credit as a remote call from inside its own local @Transactional
    // block, so a commit failure on its side replays a credit that already landed here. Same key
    // twice must mean the money moves once.
    @Test
    @DisplayName("Idempotency: crediting twice with the SAME key moves the balance once, writes one row - [MEANT TO PASS]")
    void testInternalCredit_SameIdempotencyKey_AppliedOnce() throws Exception {
        given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeChecking));
        List<TransactionEntity> ledger = givenLedgerRemembersIdempotencyKeys();

        creditRequest("{\"amount\":100.00,\"description\":\"Incoming wire\",\"idempotencyKey\":\"wire-8821\"}");
        creditRequest("{\"amount\":100.00,\"description\":\"Incoming wire\",\"idempotencyKey\":\"wire-8821\"}");

        // 1500 + 100, not + 200. The retry is answered 200 as well - the effect it asked for is in
        // place - which is what keeps the caller from retrying forever.
        assertThat(activeChecking.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("1600.00"));
        assertThat(ledger).hasSize(1);
    }

    @Test
    @DisplayName("Idempotency: crediting twice with DIFFERENT keys applies both - [MEANT TO PASS]")
    void testInternalCredit_DifferentIdempotencyKeys_BothApply() throws Exception {
        given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeChecking));
        List<TransactionEntity> ledger = givenLedgerRemembersIdempotencyKeys();

        creditRequest("{\"amount\":100.00,\"description\":\"Incoming wire\",\"idempotencyKey\":\"wire-8821\"}");
        creditRequest("{\"amount\":100.00,\"description\":\"Incoming wire\",\"idempotencyKey\":\"wire-8822\"}");

        // Two genuinely different payments that happen to be identical in every other respect are
        // still two payments - deduplicating on amount and description instead of on the caller's key
        // would quietly swallow one of them.
        assertThat(activeChecking.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("1700.00"));
        assertThat(ledger).hasSize(2);
    }

    // The compatibility guarantee that makes the field safe to add: no key means the endpoint behaves
    // exactly as it did before this existed, right down to not consulting the key index at all.
    @Test
    @DisplayName("Idempotency: crediting with NO key is unchanged - both calls apply, no lookup happens - [MEANT TO PASS]")
    void testInternalCredit_NullIdempotencyKey_UnchangedBehaviour() throws Exception {
        given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeChecking));
        List<TransactionEntity> ledger = givenLedgerRemembersIdempotencyKeys();

        creditRequest("{\"amount\":100.00,\"description\":\"Incoming wire\"}");
        creditRequest("{\"amount\":100.00,\"description\":\"Incoming wire\"}");

        assertThat(activeChecking.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("1700.00"));
        assertThat(ledger).hasSize(2);
        assertThat(ledger).allMatch(transaction -> transaction.getIdempotencyKey() == null);
        verify(transactionRepository, never()).existsByIdempotencyKey(any());
    }

    @Test
    @DisplayName("Idempotency: debiting twice with the SAME key moves the balance once, writes one row - [MEANT TO PASS]")
    void testInternalDebit_SameIdempotencyKey_AppliedOnce() throws Exception {
        given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeChecking));
        List<TransactionEntity> ledger = givenLedgerRemembersIdempotencyKeys();

        debitRequest("{\"userId\":42,\"amount\":100.00,\"description\":\"Outgoing wire\",\"idempotencyKey\":\"wire-9001\"}");
        debitRequest("{\"userId\":42,\"amount\":100.00,\"description\":\"Outgoing wire\",\"idempotencyKey\":\"wire-9001\"}");

        assertThat(activeChecking.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("1400.00"));
        assertThat(ledger).hasSize(1);
    }

    @Test
    @DisplayName("Idempotency: debiting twice with DIFFERENT keys applies both - [MEANT TO PASS]")
    void testInternalDebit_DifferentIdempotencyKeys_BothApply() throws Exception {
        given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeChecking));
        List<TransactionEntity> ledger = givenLedgerRemembersIdempotencyKeys();

        debitRequest("{\"userId\":42,\"amount\":100.00,\"description\":\"Outgoing wire\",\"idempotencyKey\":\"wire-9001\"}");
        debitRequest("{\"userId\":42,\"amount\":100.00,\"description\":\"Outgoing wire\",\"idempotencyKey\":\"wire-9002\"}");

        assertThat(activeChecking.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("1300.00"));
        assertThat(ledger).hasSize(2);
    }

    @Test
    @DisplayName("Idempotency: debiting with NO key is unchanged - both calls apply, no lookup happens - [MEANT TO PASS]")
    void testInternalDebit_NullIdempotencyKey_UnchangedBehaviour() throws Exception {
        given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeChecking));
        List<TransactionEntity> ledger = givenLedgerRemembersIdempotencyKeys();

        debitRequest("{\"userId\":42,\"amount\":100.00,\"description\":\"Outgoing wire\"}");
        debitRequest("{\"userId\":42,\"amount\":100.00,\"description\":\"Outgoing wire\"}");

        assertThat(activeChecking.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("1300.00"));
        assertThat(ledger).hasSize(2);
        verify(transactionRepository, never()).existsByIdempotencyKey(any());
    }

    private void creditRequest(String body) throws Exception {
        mockMvc.perform(post("/api/v1/internal/accounts/1/credit")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());
    }

    private void debitRequest(String body) throws Exception {
        mockMvc.perform(post("/api/v1/internal/accounts/1/debit")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());
    }

    // Stands in for the unique index from V8 with the mocked repository: every saved row is
    // remembered, and the existence check answers from what was actually written. Stubbing
    // existsByIdempotencyKey to return false-then-true would have been shorter but would pass just as
    // happily if the service stopped writing the key onto the row at all - the very thing the index
    // relies on. Returns the list so a test can assert how many rows the ledger really took.
    private List<TransactionEntity> givenLedgerRemembersIdempotencyKeys() {
        List<TransactionEntity> written = new ArrayList<>();
        given(transactionRepository.save(any(TransactionEntity.class))).willAnswer(invocation -> {
            TransactionEntity transaction = invocation.getArgument(0);
            written.add(transaction);
            return transaction;
        });
        given(transactionRepository.existsByIdempotencyKey(any())).willAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return written.stream().anyMatch(transaction -> key.equals(transaction.getIdempotencyKey()));
        });
        return written;
    }

    // Reimplements the ISO 7064 mod-97 verification side (mirrors IbanSwiftValidator over in
    // transaction-service) so this suite can confirm IbanGenerator's output is actually valid,
    // not just present.
    private boolean hasValidIbanChecksum(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder numeric = new StringBuilder();
        for (char ch : rearranged.toCharArray()) {
            if (Character.isLetter(ch)) {
                numeric.append(Character.getNumericValue(ch));
            } else {
                numeric.append(ch);
            }
        }
        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    private TransactionEntity buildTransaction(Long accountId, TransactionType type, BigDecimal amount) {
        TransactionEntity tx = new TransactionEntity();
        tx.setAccountId(accountId);
        tx.setTransactionType(type);
        tx.setAmount(amount);
        tx.setDescription("Test transaction");
        return tx;
    }
}
