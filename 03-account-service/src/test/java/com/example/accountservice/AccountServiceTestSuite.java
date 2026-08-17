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

/**
 * End-to-end HTTP tests for account-service: the customer-facing dashboard and transaction history
 * under {@code /api/v1/accounts}, the service-to-service surface under {@code /api/v1/internal/accounts},
 * the KYC gate that permits or refuses funding, and the Kafka listener that provisions a starter
 * account for a newly registered user.
 *
 * <h2>What is real and what is mocked</h2>
 * Almost everything is real. The controllers, {@code AccountService}, {@code InternalAccountService},
 * {@code AccountMapper}, {@code IbanGenerator}, {@code UserRegisteredListener}, the Spring Security
 * filter chain, the {@code InternalTokenFilter} and the {@code KycEnforcementAspect} are all the
 * production beans, wired by the real application context. Only three collaborators are replaced:
 * <ul>
 *   <li>{@link AccountRepository} and {@link TransactionRepository} are {@code @MockBean}s, so no SQL
 *       is ever issued and every test decides for itself what the database "contains". This is the
 *       single most confusing thing about this suite: a request travels the whole real stack —
 *       security, aspect, controller, service, mapper, JSON serialisation — and then hits a Mockito
 *       stub where the database would be.</li>
 *   <li>{@link ProfileServiceClient} is a {@code @MockBean} standing in for the Feign call the
 *       {@code KycEnforcementAspect} makes to profile-service before any deposit or account opening.
 *       Mocking it lets a test choose the caller's KYC status, or make the call fail outright,
 *       without profile-service running.</li>
 * </ul>
 * There is no Testcontainers, no embedded broker and no {@code MockRestServiceServer} here. Nothing
 * is published to or consumed from a real Kafka: the listener tests call
 * {@code consumeUserRegistered(...)} directly with the map a real consumer would have deserialised.
 *
 * <h2>What the mocked repositories cost us</h2>
 * The idempotency tests at the bottom are honest about what they run, but they prove less than they
 * look like they do. What actually closes the double-credit race in production is the unique index
 * {@code ux_transactions_idempotency_key} added in migration V8 — two concurrent retries can both
 * pass the application's {@code existsByIdempotencyKey} check before either inserts, and only the
 * database stops the second write. With the repositories mocked, no index exists and no concurrency
 * happens; these tests exercise the sequential fast-path check only. A regression that dropped the
 * index would sail straight through this suite.
 *
 * <h2>Fixture state and lifecycle</h2>
 * {@link #activeChecking} is rebuilt by {@link #setUp()} before <em>every</em> test
 * ({@code @BeforeEach}, not {@code @BeforeAll}), which matters because several tests mutate it: the
 * deposit, credit and debit tests assert on its balance after the service has written to the same
 * object the repository stub handed out. A shared instance would make those tests order-dependent.
 * As written, no test in this class depends on running before or after any other.
 *
 * <h2>Test configuration</h2>
 * <ul>
 *   <li><b>{@code @SpringBootTest} + {@code @AutoConfigureMockMvc} rather than {@code @WebMvcTest}.</b>
 *       A web slice would load the controllers and nothing else, and most of what this suite is
 *       actually about lives outside them: the {@code KycEnforcementAspect} (a real AOP proxy), the
 *       {@code InternalTokenFilter} registered on the servlet chain, the {@code UserRegisteredListener},
 *       and {@code IbanGenerator}. The full context buys all of that; {@code MockMvc} keeps the tests
 *       fast by driving it without a socket.</li>
 *   <li><b>H2, not Postgres.</b> {@code src/test/resources/application.properties} points the
 *       datasource at an in-memory H2 in PostgreSQL compatibility mode, with
 *       {@code spring.flyway.enabled=false} and {@code spring.jpa.hibernate.ddl-auto=none}. Because
 *       both repositories are mocked, no schema is ever needed — the datasource exists purely so the
 *       context can start, and disabling Flyway keeps this module out of the shared
 *       {@code flyway_schema_history} table that account-service and transaction-service both write
 *       to locally.</li>
 *   <li><b>No transaction management is under test.</b> Nothing here is {@code @Transactional} and
 *       there is no rollback to speak of: with the repositories mocked, "state" is whatever the
 *       fixture object holds, and {@code @BeforeEach} resets it. The service's own
 *       {@code @Transactional} boundaries and its {@code findByIdForUpdate} pessimistic lock still
 *       execute, but against a mock they are no-ops.</li>
 *   <li><b>{@code @TestPropertySource} is deliberately absent, and that is a known gap.</b> Sibling
 *       suites in other modules pin {@code app.security.internal-token} to a value that is <em>not</em>
 *       the default, so that a token hardcoded into {@code InternalTokenFilter} could not pass
 *       unnoticed. This suite does the opposite: {@link #INTERNAL_TOKEN} hardcodes
 *       {@code local-dev-internal-token}, which is exactly what {@code application.yml} resolves
 *       {@code ${INTERNAL_SERVICE_TOKEN:local-dev-internal-token}} to when the env var is unset. The
 *       token tests below therefore verify that <em>some</em> secret is required and that a wrong one
 *       is refused, but they cannot tell a filter that reads the property from a filter that compares
 *       against a baked-in constant. Pinning a different value here would close that hole.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountServiceTestSuite {

    // The shared secret guarding /api/v1/internal/**, identical in all five services. The literal is
    // the dev default from application.yml, which is what this context resolves the property to -
    // see the class Javadoc for why pinning a non-default value here would be stronger.
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

    @MockBean
    private ProfileServiceClient profileServiceClient;

    private AccountEntity activeChecking;

    @BeforeEach
    void setUp() {
        activeChecking = new AccountEntity();
        activeChecking.setId(1L);
        activeChecking.setUserId(42L);
        activeChecking.setAccountType(AccountType.CHECKING);
        // A realistic routing number and a ten-digit account number, so the masking logic has a real
        // shape to mask and the deposit/credit/debit arithmetic starts from a non-round balance.
        activeChecking.setAvailableBalance(new BigDecimal("1500.0000"));
        activeChecking.setRoutingNumber("021000021");
        activeChecking.setAccountNumber("9876543210");
        activeChecking.setStatus(AccountStatus.ACTIVE);
    }

    // Mocks the same Jwt-shaped principal the real oauth2 resource server filter builds in
    // production: the "scope" claim drives the SCOPE_FULL_AUTH authority via the default
    // JwtGrantedAuthoritiesConverter, and "userId" is what the controllers actually read.
    private static RequestPostProcessor fullAuthUser(long userId) {
        return jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", userId));
    }

    // Same idea but with a PRE_AUTH scope, simulating a session where 2FA was never completed.
    private static RequestPostProcessor preAuthUser(long userId) {
        return jwt().jwt(j -> j.claim("scope", "PRE_AUTH").claim("userId", userId));
    }

    @Test
    @DisplayName("Block 1: Dashboard queries only non-CLOSED accounts, masks the number and returns the owner's raw number - [MEANT TO PASS]")
    void getAccountsOverview_callerOwnsOneActiveAccount_queriesNonClosedAndMasksAccountNumber() throws Exception {
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

    @Test
    @DisplayName("Block 2: Dashboard returns empty array (not an error) when user has no active accounts - [MEANT TO PASS]")
    void getAccountsOverview_callerHasNoAccounts_returns200WithEmptyArray() throws Exception {
        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/accounts").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Guards against the dashboard trusting a request parameter for the caller's identity.
     * <p>
     * Read the assertion literally before trusting the intent: it proves only that a spoofed
     * {@code userId=999} does not change the response. Because {@link AccountRepository} is a mock,
     * an unstubbed {@code findByUserIdAndStatusNot(999L, ...)} would also return an empty list, so a
     * regression that <em>did</em> honour the query param would still produce an empty 200 and this
     * test would still pass. Distinguishing the two would need user 999 stubbed with accounts of
     * their own, which is beyond a rename.
     */
    @Test
    @DisplayName("Block 3: A spoofed userId query param does not change the dashboard response - [MEANT TO PASS]")
    void getAccountsOverview_spoofedUserIdQueryParam_ignoresParamAndReturns200EmptyArray() throws Exception {
        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/accounts?userId=999").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Block 4: Pre-Auth JWT (2FA incomplete) is rejected with 403 on the dashboard - [MEANT TO FAIL]")
    void getAccountsOverview_preAuthJwt_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").with(preAuthUser(42)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Block 5: Unauthenticated request is rejected on the dashboard - [MEANT TO FAIL]")
    void getAccountsOverview_unauthenticatedRequest_returns4xxClientError() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Final Block: Full-Auth user retrieves masked, filtered, correctly-priced dashboard - [MEANT TO PASS]")
    void getAccountsOverview_fullAuthOwner_returnsMaskedNumberNumericBalanceAndActiveStatus() throws Exception {
        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED))
                .willReturn(List.of(activeChecking));

        mockMvc.perform(get("/api/v1/accounts").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].maskedAccountNumber").value("......3210"))
                // A JSON number, not a quoted string - the frontend formats this client-side.
                .andExpect(jsonPath("$[0].availableBalance").value(1500.0))
                .andExpect(jsonPath("$[0].routingNumber").value("021000021"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("Block 6: Default pagination applies size=50 and DESC sort by createdAt - [MEANT TO PASS]")
    void getTransactionHistory_noPageParams_appliesSize50AndCreatedAtDescSort() throws Exception {
        given(accountRepository.findById(1L)).willReturn(Optional.of(activeChecking));
        // Stub with the exact expected Pageable so the page metadata (size=50) reflects the real request,
        // not an unpaged default - PageImpl<>(List.of()) alone reports size=0 regardless of what was asked.
        // This exact-match stub is also what proves the DESC-by-createdAt sort: any other Pageable
        // misses the stub, the mock returns null and the request fails rather than quietly passing.
        given(transactionRepository.findByAccountId(eq(1L), eq(PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")))))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")), 0));

        mockMvc.perform(get("/api/v1/accounts/1/transactions").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    @DisplayName("Block 7: type=DEBIT routes to the filtered repository query - [MEANT TO PASS]")
    void getTransactionHistory_typeDebitParam_usesFilteredQueryAndReturnsOnlyDebits() throws Exception {
        TransactionEntity debit = buildTransaction(1L, TransactionType.DEBIT, new BigDecimal("75.5000"));
        given(accountRepository.findById(1L)).willReturn(Optional.of(activeChecking));
        given(transactionRepository.findByAccountIdAndTransactionType(eq(1L), eq(TransactionType.DEBIT), any()))
                .willReturn(new PageImpl<>(List.of(debit)));

        mockMvc.perform(get("/api/v1/accounts/1/transactions").param("type", "DEBIT").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].transactionType").value("DEBIT"));
    }

    @Test
    @DisplayName("Block 8: No type filter returns all transactions via the unfiltered query - [MEANT TO PASS]")
    void getTransactionHistory_noTypeParam_usesUnfilteredQueryAndReturnsAllTransactions() throws Exception {
        TransactionEntity credit = buildTransaction(1L, TransactionType.CREDIT, new BigDecimal("200.0000"));
        TransactionEntity debit = buildTransaction(1L, TransactionType.DEBIT, new BigDecimal("50.0000"));
        given(accountRepository.findById(1L)).willReturn(Optional.of(activeChecking));
        given(transactionRepository.findByAccountId(eq(1L), any()))
                .willReturn(new PageImpl<>(List.of(credit, debit)));

        mockMvc.perform(get("/api/v1/accounts/1/transactions").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    @DisplayName("Block 9: Requesting another user's accountId is rejected with 403 - [MEANT TO FAIL]")
    void getTransactionHistory_accountOwnedByAnotherUser_returns403() throws Exception {
        AccountEntity notOwned = new AccountEntity();
        notOwned.setId(1L);
        notOwned.setUserId(999L);
        given(accountRepository.findById(1L)).willReturn(Optional.of(notOwned));

        mockMvc.perform(get("/api/v1/accounts/1/transactions").with(fullAuthUser(42)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Block 10: Non-existent accountId returns a 4xx client error, not an unhandled 500 - [MEANT TO FAIL]")
    void getTransactionHistory_unknownAccountId_returns4xxClientErrorNotUnhandled500() throws Exception {
        // AccountService.getAccountTransactions() now throws ResponseStatusException(NOT_FOUND, ...)
        // for a missing account instead of a raw IllegalArgumentException, so Spring MVC maps it to
        // a proper 404 rather than letting it escape as an unhandled 500. The assertion below is
        // deliberately the broader 4xx family rather than isNotFound().
        given(accountRepository.findById(999L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/accounts/999/transactions").with(fullAuthUser(42)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Block 11: Pre-Auth JWT cannot access transaction history - [MEANT TO FAIL]")
    void getTransactionHistory_preAuthJwt_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/1/transactions").with(preAuthUser(42)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Final Block: Owner retrieves paginated, filtered, well-formed transaction page - [MEANT TO PASS]")
    void getTransactionHistory_ownerWithExplicitPageSizeAndType_returns200WithSingleResultPageMetadata() throws Exception {
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
    void consumeUserRegistered_userHasNoAccountYet_savesZeroBalanceActiveCheckingAccount() {
        given(accountRepository.existsByUserId(777L)).willReturn(false);
        // The event carries userId as a String, the way it arrives off the wire after JSON
        // deserialisation - the listener is responsible for coercing it to a Long.
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
    void consumeUserRegistered_userAlreadyHasAccount_savesNothing() {
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
    void consumeUserRegistered_newUser_savesIbanEndingInRoutingPlusAccountNumberWithValidChecksum() {
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
    void generate_differentAccountNumbers_returnsDistinctIbansEachWithValidChecksum() {
        // The two account numbers differ only in the final digit: the mod-97 check digits must still
        // come out different, which a generator that computed them from the routing number alone
        // would fail.
        String ibanOne = ibanGenerator.generate("021000021", "100000000001");
        String ibanTwo = ibanGenerator.generate("021000021", "100000000002");

        assertThat(ibanOne).isNotEqualTo(ibanTwo);
        assertThat(hasValidIbanChecksum(ibanOne)).isTrue();
        assertThat(hasValidIbanChecksum(ibanTwo)).isTrue();
    }

    @Test
    @DisplayName("Internal IBAN lookup returns the matching account when one exists - [MEANT TO PASS]")
    void lookupByIban_ibanMatchesAnAccount_returns200WithAccountIdUserIdAndSwiftCode() throws Exception {
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
    void lookupByIban_ibanMatchesNoAccount_returns404() throws Exception {
        given(accountRepository.findByIban("XB00000000000000000000000")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/internal/accounts/lookup").param("iban", "XB00000000000000000000000")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isNotFound());
    }

    /**
     * The counterpart to the dashboard's raw-{@code accountNumber} assertion, and the reason the two
     * responses are separate records rather than one shared DTO. {@code GET /api/v1/accounts} is
     * scoped to the caller's own accounts, so returning the raw number there is fine. This lookup
     * describes SOMEBODY ELSE's account - a sender confirming who they are about to pay - so it must
     * never echo the full number back, only the masked confirmation the sender already typed.
     */
    @Test
    @DisplayName("Recipient lookup masks the number and never exposes the raw one - [MEANT TO PASS]")
    void lookupByAccountNumber_knownAccountNumber_returnsMaskedNumberAndOmitsRawNumber() throws Exception {
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
    void getAllTransactionsAcrossAccounts_callerOwnsTwoAccounts_queriesBothAccountIdsAndReturnsCombinedPage() throws Exception {
        AccountEntity secondAccount = new AccountEntity();
        secondAccount.setId(2L);
        secondAccount.setUserId(42L);
        secondAccount.setAccountType(AccountType.SAVINGS);
        secondAccount.setStatus(AccountStatus.ACTIVE);

        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED))
                .willReturn(List.of(activeChecking, secondAccount));
        // The exact-list matcher is the real assertion here: the query must be issued for BOTH owned
        // account ids. Narrowing it to one would miss the stub and return null instead of a page.
        given(transactionRepository.findByAccountIdInWithFilters(eq(List.of(1L, 2L)), isNull(), isNull(), isNull(), any()))
                .willReturn(new PageImpl<>(List.of(buildTransaction(1L, TransactionType.CREDIT, new BigDecimal("50.0000")))));

        mockMvc.perform(get("/api/v1/accounts/transactions").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    @DisplayName("Cross-account history narrowed to an accountId not owned by the caller is forbidden - [MEANT TO FAIL]")
    void getAllTransactionsAcrossAccounts_accountIdFilterNotOwnedByCaller_returns403() throws Exception {
        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED))
                .willReturn(List.of(activeChecking));

        mockMvc.perform(get("/api/v1/accounts/transactions").param("accountId", "999").with(fullAuthUser(42)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Internal by-user endpoint returns every non-CLOSED account id for the requested user - [MEANT TO PASS]")
    void getAccountIdsByUser_userOwnsTwoNonClosedAccounts_returnsBothAccountIds() throws Exception {
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

    /**
     * An unverified identity putting money into the bank is the exact thing know-your-customer rules
     * exist to stop, so this is refused the same way a transfer already is.
     */
    @Test
    @DisplayName("KYC gate: Deposit is refused with 403 while KYC is PENDING_VERIFICATION - [MEANT TO FAIL]")
    void depositFunds_kycPendingVerification_returns403NamingTheStatusAndWritesNothing() throws Exception {
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
    void depositFunds_kycApproved_creditsBalanceAndWritesTransaction() throws Exception {
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

    /** The same gate on the other direction: opening an account is the classic KYC moment. */
    @Test
    @DisplayName("KYC gate: Opening an account is refused with 403 while KYC is REJECTED - [MEANT TO FAIL]")
    void openAccount_kycRejected_returns403AndSavesNoAccount() throws Exception {
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
    void openAccount_kycApproved_returns200AndSavesAccount() throws Exception {
        given(profileServiceClient.getKycStatus(42L)).willReturn(Map.of("status", "APPROVED"));
        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED)).willReturn(List.of());

        mockMvc.perform(post("/api/v1/accounts")
                .with(fullAuthUser(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountType\":\"SAVINGS\"}"))
                .andExpect(status().isOk());

        verify(accountRepository).save(any(AccountEntity.class));
    }

    /**
     * The starter account a brand-new user receives comes from the Kafka listener, not from the
     * gated service method, so provisioning has to keep working for a user who is still PENDING - if
     * this ever regresses, registration silently stops giving new users an account at all.
     */
    @Test
    @DisplayName("KYC gate: Registration provisioning still works for a PENDING user - [MEANT TO PASS]")
    void consumeUserRegistered_kycStillPendingVerification_savesStarterAccountAnyway() {
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
    void lookupOwner_knownAccountId_returns200WithOwnerUserId() throws Exception {
        given(accountRepository.findById(1L)).willReturn(Optional.of(activeChecking));

        mockMvc.perform(get("/api/v1/internal/accounts/1/owner").header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId").value(42));
    }

    @Test
    @DisplayName("Owner lookup returns 404 for an account id that does not exist - [MEANT TO FAIL]")
    void lookupOwner_unknownAccountId_returns404() throws Exception {
        given(accountRepository.findById(999L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/internal/accounts/999/owner").header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isNotFound());
    }

    /**
     * The whole point of a dedicated single-field response instead of reusing one of the richer
     * lookup records: the caller asked who owns the account, so that is all it gets. This endpoint
     * sits under the {@code /api/v1/internal} prefix, which carries no user session at all, so
     * anything that leaks here leaks to whatever can reach the pod, not just to a logged-in owner.
     * The fixture is given a populated IBAN precisely so the {@code doesNotExist()} assertions are
     * testing omission rather than a field that happened to be null anyway.
     */
    @Test
    @DisplayName("Owner lookup exposes only the owner id, never the number, balance or IBAN - [MEANT TO PASS]")
    void lookupOwner_accountWithNumberBalanceAndIban_returnsOwnerIdOnlyWithNoOtherFields() throws Exception {
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

    /**
     * profile-service being unreachable surfaces as the Feign client throwing rather than returning
     * an empty body, which used to escape unmapped and reach the user as a 500 - reading as
     * "account-service is broken" when the gate had in fact worked exactly as intended.
     */
    @Test
    @DisplayName("KYC gate: Deposit answers 503 (not 500) when profile-service is unreachable - [MEANT TO FAIL]")
    void depositFunds_profileServiceThrowsConnectException_returns503WithErrorBodyAndWritesNothing() throws Exception {
        // Wrapped ConnectException, not a bare RuntimeException: the handler decides on the cause
        // chain, so an unwrapped throw would take a different branch.
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

    /**
     * The other half of the same failure: profile-service answers, but with nothing usable in it.
     * Treated identically - an absent status is not an approval.
     */
    @Test
    @DisplayName("KYC gate: Deposit answers 503 when profile-service returns a body with no status - [MEANT TO FAIL]")
    void depositFunds_profileServiceBodyHasNoStatus_returns503AndWritesNothing() throws Exception {
        given(profileServiceClient.getKycStatus(42L)).willReturn(Map.of("userId", "42"));

        mockMvc.perform(post("/api/v1/accounts/1/deposit")
                .with(fullAuthUser(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100.00}"))
                .andExpect(status().isServiceUnavailable());

        verify(accountRepository, never()).save(any(AccountEntity.class));
        verify(transactionRepository, never()).save(any(TransactionEntity.class));
    }

    /**
     * Opening an account goes through the same aspect, so it must report the same status rather than
     * falling back to the old 500 on whichever path happened not to be covered.
     */
    @Test
    @DisplayName("KYC gate: Opening an account answers 503 when profile-service is unreachable - [MEANT TO FAIL]")
    void openAccount_profileServiceThrowsConnectException_returns503AndSavesNoAccount() throws Exception {
        given(profileServiceClient.getKycStatus(42L))
                .willThrow(new RuntimeException("connect timed out", new ConnectException("Connection refused")));

        mockMvc.perform(post("/api/v1/accounts")
                .with(fullAuthUser(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountType\":\"SAVINGS\"}"))
                .andExpect(status().isServiceUnavailable());

        verify(accountRepository, never()).save(any(AccountEntity.class));
    }

    /**
     * Guards the distinction the 503 introduces: "we could not check" must not have swallowed "we
     * checked and you are not approved". Deliberately a near-duplicate of
     * {@link #depositFunds_kycPendingVerification_returns403NamingTheStatusAndWritesNothing()} - it
     * exists to pin the 403 in place now that a second failure status shares the same code path.
     */
    @Test
    @DisplayName("KYC gate: An unapproved status is still 403, not the new 503 - [MEANT TO FAIL]")
    void depositFunds_kycPendingVerificationNotAServiceFailure_returns403RatherThan503() throws Exception {
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

    /**
     * The approving half of the same pair as
     * {@link #depositFunds_kycPendingVerificationNotAServiceFailure_returns403RatherThan503()}: the
     * new fail-closed 503 branch must not have started catching the happy path too.
     */
    @Test
    @DisplayName("KYC gate: An APPROVED status still completes the deposit - [MEANT TO PASS]")
    void depositFunds_kycApprovedNotAServiceFailure_stillCreditsBalanceRatherThan503() throws Exception {
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

    /**
     * The endpoint this whole layer exists for. Before the filter, anything that could open a socket
     * to this service's port could add any amount to any account with no credential whatsoever.
     */
    @Test
    @DisplayName("Internal token: credit with no X-Internal-Token is refused 401 and touches nothing - [MEANT TO FAIL]")
    void credit_missingInternalTokenHeader_returns401AndNeverTouchesRepositories() throws Exception {
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
    void credit_wrongInternalTokenHeader_returns401WithMessageNamingNeitherHeaderNorProperty() throws Exception {
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
    @DisplayName("Internal token: credit with the correct X-Internal-Token applies the credit and writes the ledger row - [MEANT TO PASS]")
    void credit_correctInternalTokenHeader_creditsBalanceAndWritesTransaction() throws Exception {
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
    void debit_missingInternalTokenHeader_returns401AndNeverTouchesRepositories() throws Exception {
        mockMvc.perform(post("/api/v1/internal/accounts/1/debit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":42,\"amount\":100.00,\"description\":\"Outgoing wire\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionRepository);
    }

    /**
     * The scoping half of the contract, and the reason the filter checks the path prefix at all: the
     * secret guards service-to-service traffic only. If it ever leaked onto the customer-facing API,
     * every logged-in browser would have to hold a server credential to see its own dashboard.
     */
    @Test
    @DisplayName("Internal token: the customer-facing JWT API still works with no X-Internal-Token - [MEANT TO PASS]")
    void getAccountsOverview_jwtCallerWithoutInternalTokenHeader_returns200WithOneAccount() throws Exception {
        given(accountRepository.findByUserIdAndStatusNot(42L, AccountStatus.CLOSED))
                .willReturn(List.of(activeChecking));

        mockMvc.perform(get("/api/v1/accounts").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ==========================================
    // Idempotent ledger writes (optional idempotencyKey on credit/debit/transfer)
    //
    // Note what these can and cannot prove: with TransactionRepository mocked, the V8 unique index
    // ux_transactions_idempotency_key does not exist, and no two requests here are concurrent. These
    // tests cover the service's sequential existsByIdempotencyKey fast path only - the index that
    // actually closes the check-then-act race is never exercised.
    // ==========================================

    /**
     * transaction-service issues credit as a remote call from inside its own local
     * {@code @Transactional} block, so a commit failure on its side replays a credit that already
     * landed here. Same key twice must mean the money moves once.
     */
    @Test
    @DisplayName("Idempotency: crediting twice with the SAME key moves the balance once, writes one row - [MEANT TO PASS]")
    void credit_sameIdempotencyKeyTwice_movesBalanceOnceAndWritesOneRow() throws Exception {
        given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeChecking));
        List<TransactionEntity> ledger = givenLedgerRemembersIdempotencyKeys();

        creditRequest("{\"amount\":100.00,\"description\":\"Incoming wire\",\"idempotencyKey\":\"wire-8821\"}");
        creditRequest("{\"amount\":100.00,\"description\":\"Incoming wire\",\"idempotencyKey\":\"wire-8821\"}");

        // 1500 + 100, not + 200. The retry is answered 200 as well - the effect it asked for is in
        // place - which is what keeps the caller from retrying forever. That 200 is asserted inside
        // creditRequest(), so a replay answered with an error would fail this test too.
        assertThat(activeChecking.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("1600.00"));
        assertThat(ledger).hasSize(1);
    }

    @Test
    @DisplayName("Idempotency: crediting twice with DIFFERENT keys applies both - [MEANT TO PASS]")
    void credit_differentIdempotencyKeys_movesBalanceTwiceAndWritesTwoRows() throws Exception {
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

    /**
     * The compatibility guarantee that makes the field safe to add: no key means the endpoint behaves
     * exactly as it did before this existed, right down to not consulting the key index at all.
     */
    @Test
    @DisplayName("Idempotency: crediting with NO key is unchanged - both calls apply, no lookup happens - [MEANT TO PASS]")
    void credit_noIdempotencyKey_appliesBothCallsAndNeverQueriesTheKeyIndex() throws Exception {
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
    void debit_sameIdempotencyKeyTwice_movesBalanceOnceAndWritesOneRow() throws Exception {
        given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeChecking));
        List<TransactionEntity> ledger = givenLedgerRemembersIdempotencyKeys();

        debitRequest("{\"userId\":42,\"amount\":100.00,\"description\":\"Outgoing wire\",\"idempotencyKey\":\"wire-9001\"}");
        debitRequest("{\"userId\":42,\"amount\":100.00,\"description\":\"Outgoing wire\",\"idempotencyKey\":\"wire-9001\"}");

        assertThat(activeChecking.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("1400.00"));
        assertThat(ledger).hasSize(1);
    }

    @Test
    @DisplayName("Idempotency: debiting twice with DIFFERENT keys applies both - [MEANT TO PASS]")
    void debit_differentIdempotencyKeys_movesBalanceTwiceAndWritesTwoRows() throws Exception {
        given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeChecking));
        List<TransactionEntity> ledger = givenLedgerRemembersIdempotencyKeys();

        debitRequest("{\"userId\":42,\"amount\":100.00,\"description\":\"Outgoing wire\",\"idempotencyKey\":\"wire-9001\"}");
        debitRequest("{\"userId\":42,\"amount\":100.00,\"description\":\"Outgoing wire\",\"idempotencyKey\":\"wire-9002\"}");

        assertThat(activeChecking.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("1300.00"));
        assertThat(ledger).hasSize(2);
    }

    @Test
    @DisplayName("Idempotency: debiting with NO key is unchanged - both calls apply, no lookup happens - [MEANT TO PASS]")
    void debit_noIdempotencyKey_appliesBothCallsAndNeverQueriesTheKeyIndex() throws Exception {
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
