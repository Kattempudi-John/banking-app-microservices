package com.example.transactionservice;

import com.example.transactionservice.aspect.KycEnforcementAspect;
import com.example.transactionservice.client.AccountServiceClient;
import com.example.transactionservice.client.AuthServiceClient;
import com.example.transactionservice.client.ProfileServiceClient;
import com.example.transactionservice.controller.TransferController.InternalTransferRequestDto;
import com.example.transactionservice.event.FundsTransferredEvent;
import com.example.transactionservice.event.LargeTransferRequestedEvent;
import com.example.transactionservice.model.TransactionEntity;
import com.example.transactionservice.model.TransactionStatus;
import com.example.transactionservice.repository.TransactionRepository;
import com.example.transactionservice.service.ExternalWireService;
import com.example.transactionservice.service.TransferService;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Money-movement slice of transaction-service: {@code TransferService}, {@code ExternalWireService},
 * the {@code FraudResolutionService} behind {@code InternalFraudController}, and the two controllers
 * in front of them ({@code TransferController} and {@code InternalFraudController}).
 *
 * <h2>Why {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} rather than {@code @WebMvcTest}</h2>
 * Almost everything this suite is here to prove lives <em>outside</em> the MVC layer that a
 * {@code @WebMvcTest} slice would give us:
 * <ul>
 *   <li>{@code KycEnforcementAspect} is AOP around {@code @RequiresKyc} service methods - a web slice
 *       does not create the proxies, so every KYC test would silently pass through ungated code.</li>
 *   <li>{@code InternalTokenFilter} is a servlet filter in the real security chain; the shared-secret
 *       tests are meaningless without it.</li>
 *   <li>The Kafka publish is driven by {@code @TransactionalEventListener(phase = AFTER_COMMIT)},
 *       which needs a real transaction manager and a real event multicaster.</li>
 *   <li>The outbound Feign {@code RequestInterceptor} from {@code FeignInternalTokenConfig} is an
 *       ordinary bean that only a full context contributes.</li>
 * </ul>
 * The trade-off is boot cost, paid once per cached context for the whole class.
 *
 * <h2>What is real, what is mocked</h2>
 * Real: both services, the KYC aspect, {@code IbanSwiftValidator}, the security filter chain,
 * {@code GlobalExceptionHandler}, the event publisher, and the Feign request interceptor. Mocked with
 * {@code @MockBean}:
 * <ul>
 *   <li>{@code AccountServiceClient} - every balance mutation in this system happens in
 *       account-service, so this mock is how the suite both simulates its rejections (insufficient
 *       funds, ownership mismatch, unknown IBAN) and proves that transaction-service delegates
 *       instead of touching a balance itself.</li>
 *   <li>{@code ProfileServiceClient} - the KYC verdict for both the caller and the recipient.</li>
 *   <li>{@code AuthServiceClient} - only the recipient preview uses it (to resolve a display name),
 *       but it must still be mocked: an unmocked Feign client would attempt a live call to
 *       auth-service on port 8081.</li>
 *   <li>{@code TransactionRepository} - no row is ever written or read; assertions inspect the
 *       captured entity instead. Nothing here is transactional at the test level, so there is no
 *       rollback to reason about and no database state to leak between tests. The H2 datasource in
 *       {@code src/test/resources/application.properties} exists purely so JPA auto-configuration can
 *       start (Flyway off, {@code ddl-auto=none}); it is never queried.</li>
 *   <li>Both {@code KafkaTemplate}s - Kafka auto-configuration is excluded outright, so there is no
 *       broker, embedded or otherwise. The templates are asserted against, never sent through.</li>
 * </ul>
 *
 * <h2>Test configuration</h2>
 * {@code spring.autoconfigure.exclude} drops {@code KafkaAutoConfiguration} so the context starts
 * without a broker on the machine.
 * <p>
 * {@code application.security.internal-token} is pinned to a value that is deliberately <em>not</em>
 * the dev default baked into {@code InternalTokenFilter} / {@code FeignInternalTokenConfig}. If either
 * one ignored the property and compared against a hardcoded constant, a suite that used the default
 * would still go green and would prove nothing about how the secret behaves once k8s overrides it.
 * Pinning a different value is the only way the tests can fail on that mistake.
 *
 * <h2>Two calling styles, on purpose</h2>
 * Tests that assert an HTTP status, a JSON body, or filter/validation behaviour go through
 * {@code MockMvc} with a {@code jwt()} post-processor. Tests that assert business behaviour call
 * {@code TransferService} / {@code ExternalWireService} directly, which keeps them free of request
 * plumbing but means {@code KycEnforcementAspect} still has to find a caller: it reads the
 * {@code userId} claim off a {@code Jwt} principal in {@code SecurityContextHolder}, not the
 * {@code userId} argument. {@link #authenticateAsFullAuthUser(long)} installs exactly that, so a
 * direct call hits the aspect the way a real request would. {@code @WithMockUser} is not
 * interchangeable here - its plain {@code User} principal fails the cast to {@code Jwt} - and is used
 * only where the test is about the authorization scope rather than the caller identity.
 *
 * <h2>Shared fixture state</h2>
 * {@code @BeforeEach} stubs the caller (user 42) as KYC-APPROVED so the common path works without
 * ceremony; the KYC-rejection tests re-stub it. {@code @AfterEach} clears the
 * {@code SecurityContextHolder}, which is thread-local and would otherwise carry an authenticated
 * principal into the next test in the same thread. There is no {@code @BeforeAll} state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
    // Deliberately NOT the dev default baked into InternalTokenFilter/FeignInternalTokenConfig:
    // if either one ignored the property and used a hardcoded constant, these tests would still
    // pass against the default and prove nothing about how it behaves once k8s overrides it.
    "application.security.internal-token=test-suite-internal-token"
})
class TransferServiceTestSuite {

    // Spelled out again rather than referenced from the annotation above, which only accepts
    // compile-time literals.
    private static final String INTERNAL_TOKEN = "test-suite-internal-token";
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransferService transferService;

    @Autowired
    private ExternalWireService externalWireService;

    // The real bean FeignInternalTokenConfig contributes. Every Feign client in this module is a
    // @MockBean here, so the only way to prove the outbound half of the contract is to exercise the
    // interceptor itself against a RequestTemplate.
    @Autowired
    private RequestInterceptor internalTokenRequestInterceptor;

    @MockBean
    private AccountServiceClient accountServiceClient;

    @MockBean
    private TransactionRepository transactionRepository;

    @MockBean
    private ProfileServiceClient profileServiceClient;

    @MockBean
    private AuthServiceClient authServiceClient;

    @MockBean
    private KafkaTemplate<String, FundsTransferredEvent> fundsTransferredKafkaTemplate;

    @MockBean
    private KafkaTemplate<String, LargeTransferRequestedEvent> largeTransferKafkaTemplate;

    // A canonical, checksum-valid IBAN (ISO 7064 MOD 97-10) used across banking test suites.
    private static final String VALID_IBAN = "GB29NWBK60161331926819";
    private static final String VALID_SWIFT = "DEUTDEFF";

    // Default: caller is KYC-APPROVED. Individual KYC-rejection tests override this stub.
    @BeforeEach
    void setUp() {
        given(profileServiceClient.getKycStatus(42L)).willReturn(Map.of("status", "APPROVED"));
    }

    // SecurityContextHolder is thread-local and survives the test method, so an authenticated
    // principal would otherwise leak into whatever runs next on this thread.
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // KycEnforcementAspect casts the SecurityContext principal to a Jwt and reads its "userId" claim
    // (not the JWT subject), so a Jwt-shaped Authentication is what makes @RequiresKyc reachable from
    // a direct service-layer call.
    private static Authentication jwtAuthentication(long userId, String scope) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("test-user")
                .claim("scope", scope)
                .claim("userId", userId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
        return new JwtAuthenticationToken(jwt, new JwtGrantedAuthoritiesConverter().convert(jwt));
    }

    private void authenticateAsFullAuthUser(long userId) {
        SecurityContextHolder.getContext().setAuthentication(jwtAuthentication(userId, "FULL_AUTH"));
    }

    /**
     * The balance check moved into account-service's {@code InternalAccountController}, so the only
     * thing transaction-service can be held to is that it surfaces the rejection unchanged. The stub
     * throws exactly what {@code FeignErrorConfig} translates a 400 {@code INSUFFICIENT_FUNDS}
     * response into.
     */
    @Test
    @DisplayName("Block 1: Insufficient funds rejects transfer with INSUFFICIENT_FUNDS - [MEANT TO FAIL]")
    void executeTransfer_accountServiceRejectsForInsufficientFunds_propagatesBadRequest() {
        authenticateAsFullAuthUser(42);
        willThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_FUNDS"))
                .given(accountServiceClient).transfer(any());

        assertThatThrownBy(() ->
                transferService.executeTransfer(42L, 1L, 2L, new BigDecimal("5000.00")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INSUFFICIENT_FUNDS");
    }

    /**
     * Ownership is enforced in account-service too; the stub stands in for its 403 so this side's
     * pass-through can be asserted.
     */
    @Test
    @DisplayName("Block 2: Transfer between accounts not owned by the caller is forbidden - [MEANT TO FAIL]")
    void executeTransfer_accountsNotOwnedByCaller_propagatesForbidden() {
        authenticateAsFullAuthUser(42);
        willThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Both accounts must belong to the authenticated user"))
                .given(accountServiceClient).transfer(any());

        assertThatThrownBy(() ->
                transferService.executeTransfer(42L, 1L, 2L, new BigDecimal("100.00")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Both accounts must belong to the authenticated user");
    }

    /**
     * The pessimistic lock now lives in account-service (covered end-to-end there), so the equivalent
     * guarantee on this side is delegation: the request must arrive intact and no balance may be
     * mutated locally. The {@code verify} on an exact {@code TransferRequest} is what proves the
     * second half - any local shortcut would show up as a missing or altered call.
     */
    @Test
    @DisplayName("Block 3: Transfer delegates the balance mutation to account-service with the correct request - [MEANT TO PASS]")
    void executeTransfer_validRequest_delegatesBalanceMutationToAccountService() {
        authenticateAsFullAuthUser(42);

        transferService.executeTransfer(42L, 1L, 2L, new BigDecimal("100.00"));

        verify(accountServiceClient).transfer(new AccountServiceClient.TransferRequest(42L, 1L, 2L, new BigDecimal("100.00")));
    }

    /**
     * Deliberately NOT {@code @Transactional} at the test level: the production publish runs from
     * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, which never fires if the test wraps
     * the call in a transaction that gets rolled back at the end. A test-level {@code @Transactional}
     * here would turn the Kafka assertion into a permanent false negative.
     * <p>
     * This is also the only test that reaches {@code TransferController.extractUserIdFromAuth()},
     * which casts the principal to a {@code Jwt} - {@code @WithMockUser}'s {@code User} principal
     * would fail that cast.
     */
    @Test
    @DisplayName("Final Block: Successful internal transfer commits balances, returns confirmation ID, and publishes FundsTransferredEvent to Kafka AFTER commit - [MEANT TO PASS]")
    void executeInternalTransfer_validRequest_returns200AndPublishesFundsTransferredAfterCommit() throws Exception {
        InternalTransferRequestDto request = new InternalTransferRequestDto(1L, 2L, new BigDecimal("100.00"));

        mockMvc.perform(post("/api/v1/transfers/internal")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").exists())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(accountServiceClient).transfer(new AccountServiceClient.TransferRequest(42L, 1L, 2L, new BigDecimal("100.00")));
        verify(fundsTransferredKafkaTemplate).send(eq("successful-transfers"), any(String.class), any(FundsTransferredEvent.class));
    }

    /**
     * Raw JSON rather than a DTO instance, because the point is the shape that arrives over the wire:
     * {@code ExternalWireRequestDto}'s Jakarta {@code @Pattern} rejects it during binding, before any
     * business logic runs.
     */
    @Test
    @DisplayName("Block 4: Structurally invalid IBAN rejected by bean validation before reaching the service - [MEANT TO FAIL]")
    @WithMockUser(username = "42", authorities = {"SCOPE_FULL_AUTH"})
    void executeExternalWire_structurallyMalformedIban_returns400() throws Exception {
        String payload = """
                {"iban":"NOT_AN_IBAN","swiftCode":"DEUTDEFF","beneficiaryName":"John Smith","amount":100.00}
                """;

        mockMvc.perform(post("/api/v1/transfers/external")
                .param("fromAccountId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest());
    }

    /**
     * One level deeper than the malformed-IBAN case: this string is the right shape, so a regex would
     * wave it through. Only the MOD 97 arithmetic in {@code IbanSwiftValidator} catches it, which is
     * why the test calls the service directly rather than relying on the DTO's {@code @Pattern}.
     */
    @Test
    @DisplayName("Block 5: Structurally valid but checksum-invalid IBAN rejected by IbanSwiftValidator - [MEANT TO FAIL]")
    void initiateWire_ibanFailingMod97Checksum_throwsInvalidFormat() {
        authenticateAsFullAuthUser(42);

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                "GB30NWBK60161331926819", // one digit off from VALID_IBAN -> fails the MOD 97 checksum
                VALID_SWIFT, "John Smith", new BigDecimal("100.00"));

        assertThatThrownBy(() -> externalWireService.initiateWire(42L, 1L, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid IBAN or SWIFT code format");
    }

    /**
     * The wire equivalent of the internal-transfer case: account-service's debit endpoint owns the
     * balance check, so the stub throws its translated rejection and this side must surface it
     * unchanged rather than dressing it up as something else.
     */
    @Test
    @DisplayName("Block 6: Insufficient funds rejects the external wire with INSUFFICIENT_FUNDS - [MEANT TO FAIL]")
    void initiateWire_debitRejectedForInsufficientFunds_propagatesBadRequest() {
        authenticateAsFullAuthUser(42);
        willThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_FUNDS"))
                .given(accountServiceClient).debit(eq(1L), any());

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT, "John Smith", new BigDecimal("5000.01"));

        assertThatThrownBy(() -> externalWireService.initiateWire(42L, 1L, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INSUFFICIENT_FUNDS");
    }

    /**
     * The lower half of the fraud-review boundary. The rule is <em>strictly greater than</em> $5000,
     * so $5000.00 exactly is the largest amount that must still clear without review - one cent more
     * is the paired case below.
     * <p>
     * account-service's debit is not stubbed here on purpose: a mocked void method does nothing unless
     * told to throw, which is the "debit succeeded" path.
     */
    @Test
    @DisplayName("Block 7: Wire of exactly $5000 completes immediately without a fraud event - [MEANT TO PASS]")
    void initiateWire_amountExactlyAtFraudThreshold_completesWithoutFraudEvent() {
        authenticateAsFullAuthUser(42);

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT, "John Smith", new BigDecimal("5000.00"));

        var response = externalWireService.initiateWire(42L, 1L, request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(largeTransferKafkaTemplate, never()).send(any(), any(), any());
    }

    /**
     * The upper half of the same boundary. Funds are reserved up front (debit) but the wire is not
     * released, and the review event carries the transaction id so the fraud team's verdict can be
     * routed back to this exact wire.
     */
    @Test
    @DisplayName("Final Block: Wire over $5000 is pre-reserved, marked PENDING_APPROVAL, and publishes LargeTransferRequestedEvent - [MEANT TO PASS]")
    void initiateWire_amountOverFraudThreshold_holdsFundsAndPublishesLargeTransferEvent() {
        authenticateAsFullAuthUser(42);

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT, "John Smith", new BigDecimal("7500.00"));

        var response = externalWireService.initiateWire(42L, 1L, request);

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        verify(accountServiceClient).debit(eq(1L), eq(new AccountServiceClient.DebitRequest(
                42L, new BigDecimal("7500.00"), "External Wire to John Smith")));
        verify(transactionRepository).save(any());
        verify(largeTransferKafkaTemplate).send(eq("large-transfers-review"), eq(response.transactionId().toString()), any(LargeTransferRequestedEvent.class));
    }

    /**
     * {@code SCOPE_PRE_AUTH} is the token issued after a password but before 2FA is finished. It is a
     * real, valid, authenticated session - which is exactly why the endpoint has to reject it on
     * scope rather than on authentication.
     */
    @Test
    @WithMockUser(username = "42", authorities = {"SCOPE_PRE_AUTH"})
    @DisplayName("Block 8: Pre-Auth (partial 2FA) token denied on internal transfer endpoint - [MEANT TO FAIL]")
    void executeInternalTransfer_preAuthScopeOnly_returns403() throws Exception {
        InternalTransferRequestDto request = new InternalTransferRequestDto(1L, 2L, new BigDecimal("10.00"));

        mockMvc.perform(post("/api/v1/transfers/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Block 9: Unauthenticated request denied on internal transfer endpoint - [MEANT TO FAIL]")
    void executeInternalTransfer_noAuthentication_returns4xx() throws Exception {
        InternalTransferRequestDto request = new InternalTransferRequestDto(1L, 2L, new BigDecimal("10.00"));

        mockMvc.perform(post("/api/v1/transfers/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Block 10: Non-APPROVED KYC status blocks an internal transfer - [MEANT TO FAIL]")
    void executeTransfer_callerKycPendingVerification_throwsKycRequiredAndMovesNoMoney() {
        authenticateAsFullAuthUser(42);
        given(profileServiceClient.getKycStatus(42L)).willReturn(Map.of("status", "PENDING_VERIFICATION"));

        // The frontend shows this text to the sender verbatim, so it is written for them and does
        // not carry the raw status value - same rule the dashboard notice follows.
        assertThatThrownBy(() -> transferService.executeTransfer(42L, 1L, 2L, new BigDecimal("50.00")))
                .isInstanceOf(KycEnforcementAspect.KycRequiredException.class)
                .hasMessageContaining("Transfers are disabled until your identity is verified")
                .hasMessageNotContaining("PENDING_VERIFICATION");

        verify(accountServiceClient, never()).transfer(any());
    }

    @Test
    @DisplayName("Final Block: Non-APPROVED KYC status blocks an external wire before any funds move - [MEANT TO FAIL]")
    void initiateWire_callerKycRejected_throwsKycRequiredBeforeAnyFundsMove() {
        authenticateAsFullAuthUser(42);
        given(profileServiceClient.getKycStatus(42L)).willReturn(Map.of("status", "REJECTED"));

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT, "John Smith", new BigDecimal("100.00"));

        // Deliberately the same wording for a REJECTED applicant as for a pending one: telling them
        // to "complete" verification would be false, since a rejection cannot be cleared by
        // resubmitting the form.
        assertThatThrownBy(() -> externalWireService.initiateWire(42L, 1L, request))
                .isInstanceOf(KycEnforcementAspect.KycRequiredException.class)
                .hasMessageContaining("Transfers are disabled until your identity is verified")
                .hasMessageNotContaining("REJECTED");

        verify(accountServiceClient, never()).debit(any(), any());
        verify(transactionRepository, never()).save(any());
    }

    /**
     * {@code GlobalExceptionHandler} is what turns {@code KycRequiredException} into a real 403 over
     * HTTP. The two tests above call the service directly and so bypass the
     * {@code @RestControllerAdvice} entirely; this one goes through MockMvc specifically to prove a
     * real request gets 403 rather than the unhandled 500 the aspect's own Javadoc used to promise.
     */
    @Test
    @DisplayName("Block 11: Non-APPROVED KYC status returns HTTP 403 (not an unhandled 500) - [MEANT TO FAIL]")
    void executeInternalTransfer_callerKycNotApproved_returns403WithSenderFacingReason() throws Exception {
        given(profileServiceClient.getKycStatus(42L)).willReturn(Map.of("status", "PENDING_VERIFICATION"));
        InternalTransferRequestDto request = new InternalTransferRequestDto(1L, 2L, new BigDecimal("50.00"));

        mockMvc.perform(post("/api/v1/transfers/internal")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                // The body is what the sender reads: the reason has to arrive intact, because the
                // page now shows whatever the server said instead of substituting its own line.
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("Transfers are disabled until your identity is verified")))
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PENDING_VERIFICATION"))));
    }

    /**
     * The other half of the 403 case: not "we asked and the answer was no" but "we could not ask at
     * all". The gate still fails closed - an unreachable profile-service is never read as approval -
     * but the caller is told it is a retryable outage (503) rather than an unhandled crash (500, what
     * a bare Feign failure used to produce) or a verdict on them (403).
     */
    @Test
    @DisplayName("Block 12: Unreachable profile-service returns HTTP 503 and moves no money - [MEANT TO FAIL]")
    void executeInternalTransfer_profileServiceUnreachable_returns503AndMovesNoMoney() throws Exception {
        // What Feign throws when the connection can't be made at all, rather than an HTTP error body.
        given(profileServiceClient.getKycStatus(42L)).willThrow(new RuntimeException("Connection refused: connect"));
        InternalTransferRequestDto request = new InternalTransferRequestDto(1L, 2L, new BigDecimal("50.00"));

        mockMvc.perform(post("/api/v1/transfers/internal")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("couldn't confirm your identity verification")))
                // account-service uses "message"; both keys carry the same text so either client helper reads it
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("try again in a moment")));

        verify(accountServiceClient, never()).transfer(any());
    }

    // ==========================================
    // On-network transfers via IBAN: an external wire whose IBAN resolves to an account on this
    // platform executes as a real transfer (debit + credit) instead of the debit-only simulation
    // used for a genuinely external destination.
    // ==========================================

    @Test
    @DisplayName("On-us wire under threshold resolves via IBAN and credits the destination immediately - [MEANT TO PASS]")
    void initiateWire_onUsIbanUnderThreshold_creditsDestinationImmediately() {
        authenticateAsFullAuthUser(42);
        given(accountServiceClient.lookupByIban(VALID_IBAN))
                .willReturn(new AccountServiceClient.AccountLookupResponse(99L, 55L, "CHECKING", "ACTIVE", VALID_SWIFT));
        // An IBAN that resolves here means a real user (55) is being credited, and the recipient-side
        // KYC check fails closed - so this legitimate on-us wire has to say they're approved.
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "APPROVED"));

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT, "Jane Doe", new BigDecimal("100.00"));

        var response = externalWireService.initiateWire(42L, 1L, request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.onUsTransfer()).isTrue();
        verify(accountServiceClient).debit(eq(1L), any());
        verify(accountServiceClient).credit(eq(99L), any());
        verify(largeTransferKafkaTemplate, never()).send(any(), any(), any());
    }

    // ==========================================
    // IBAN / BIC pairing on an on-us wire
    // ==========================================

    /**
     * The IBAN and the BIC on one wire are supposed to name the same bank. {@code isValidIban} runs a
     * real MOD 97 checksum so a wrong IBAN is caught, but a BIC has no check digit at all - any
     * well-formed string passed, and the destination was resolved from the IBAN alone, so a wire
     * addressed to some other bank still landed in one of our accounts.
     */
    @Test
    @DisplayName("On-us wire with a BIC belonging to a different bank is rejected - [MEANT TO FAIL]")
    void initiateWire_onUsIbanWithBicOfDifferentBank_rejectsBeforeDebit() {
        authenticateAsFullAuthUser(42);
        given(accountServiceClient.lookupByIban(VALID_IBAN))
                .willReturn(new AccountServiceClient.AccountLookupResponse(99L, 55L, "CHECKING", "ACTIVE", VALID_SWIFT));

        // Same IBAN, one character different in the BIC - still a structurally valid BIC, which is
        // exactly why the format check cannot catch it.
        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, "DEUTDEFX", "Jane Doe", new BigDecimal("100.00"));

        assertThatThrownBy(() -> externalWireService.initiateWire(42L, 1L, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("doesn't match the bank holding this IBAN");

        // Nothing may move: the rejection has to happen before the sender is debited.
        verify(accountServiceClient, never()).debit(any(), any());
        verify(accountServiceClient, never()).credit(any(), any());
    }

    /**
     * Ordering matters here, not just the outcome. The pairing is checked before the recipient's KYC
     * status so that a sender who typed the wrong bank is told that - rather than being handed a 403
     * describing the recipient, which would leak whether that person is verified to someone who
     * addressed the wire incorrectly. The recipient is stubbed unverified precisely so a
     * wrong-order implementation would produce a {@code KycRequiredException} instead.
     */
    @Test
    @DisplayName("A mismatched BIC is reported as such, not as a recipient KYC failure - [MEANT TO FAIL]")
    void initiateWire_mismatchedBicAndUnverifiedRecipient_reportsBicMismatchNotKycFailure() {
        authenticateAsFullAuthUser(42);
        given(accountServiceClient.lookupByIban(VALID_IBAN))
                .willReturn(new AccountServiceClient.AccountLookupResponse(99L, 55L, "CHECKING", "ACTIVE", VALID_SWIFT));
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "PENDING_VERIFICATION"));

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, "DEUTDEFX", "Jane Doe", new BigDecimal("100.00"));

        assertThatThrownBy(() -> externalWireService.initiateWire(42L, 1L, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("doesn't match the bank holding this IBAN");
    }

    /**
     * Case and stray whitespace are not a different bank, so the comparison must not treat them as
     * one. This exercises the service directly on purpose: over HTTP,
     * {@code ExternalWireRequestDto}'s {@code @Pattern} already requires an upper-case BIC and would
     * reject {@code "deutdeff"} with a 400 before this code is reached. That upper-case-only contract
     * predates the pairing check and is unchanged - the normalization exists so the comparison stays
     * correct on its own terms instead of silently depending on a caller having upper-cased first.
     */
    @Test
    @DisplayName("On-us BIC comparison ignores case and surrounding whitespace - [MEANT TO PASS]")
    void initiateWire_onUsBicDifferingOnlyByCase_completesAndCreditsDestination() {
        authenticateAsFullAuthUser(42);
        given(accountServiceClient.lookupByIban(VALID_IBAN))
                .willReturn(new AccountServiceClient.AccountLookupResponse(99L, 55L, "CHECKING", "ACTIVE", VALID_SWIFT));
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "APPROVED"));

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT.toLowerCase(), "Jane Doe", new BigDecimal("100.00"));

        var response = externalWireService.initiateWire(42L, 1L, request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(accountServiceClient).credit(eq(99L), any());
    }

    /**
     * A wire to a bank that genuinely is not us keeps working: there is no directory to check the
     * pairing against, and refusing a correct BIC we simply cannot verify would be worse than not
     * checking.
     */
    @Test
    @DisplayName("Genuinely external wire is unaffected by the BIC pairing rule - [MEANT TO PASS]")
    void initiateWire_unresolvedIbanWithForeignBic_skipsPairingCheckAndStaysExternal() {
        authenticateAsFullAuthUser(42);
        given(accountServiceClient.lookupByIban(VALID_IBAN))
                .willThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, "DEUTDEFX", "Jane Doe", new BigDecimal("100.00"));

        var response = externalWireService.initiateWire(42L, 1L, request);

        assertThat(response.onUsTransfer()).isFalse();
        verify(accountServiceClient).debit(eq(1L), any());
    }

    /**
     * The counterpart to the under-threshold on-us wire: being on-us must not shortcut fraud review.
     * At initiation the destination gets nothing - only the hold is taken - and its half of the
     * transfer waits for the approval covered further down.
     */
    @Test
    @DisplayName("On-us wire over threshold holds funds without crediting the destination yet - [MEANT TO PASS]")
    void initiateWire_onUsIbanOverThreshold_holdsFundsWithoutCreditingDestination() {
        authenticateAsFullAuthUser(42);
        given(accountServiceClient.lookupByIban(VALID_IBAN))
                .willReturn(new AccountServiceClient.AccountLookupResponse(99L, 55L, "CHECKING", "ACTIVE", VALID_SWIFT));
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "APPROVED"));

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT, "Jane Doe", new BigDecimal("7500.00"));

        var response = externalWireService.initiateWire(42L, 1L, request);

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.onUsTransfer()).isTrue();
        verify(accountServiceClient).debit(eq(1L), any());
        verify(accountServiceClient, never()).credit(any(), any());
        verify(largeTransferKafkaTemplate).send(eq("large-transfers-review"), any(), any(LargeTransferRequestedEvent.class));
    }

    /**
     * The regression guard for the whole on-us feature: an IBAN belonging to no account here
     * (account-service 404, translated by {@code FeignErrorConfig}) must behave exactly as wires did
     * before on-us resolution existed.
     */
    @Test
    @DisplayName("Wire with an unresolved IBAN stays a genuinely external, non-on-us wire - [MEANT TO PASS]")
    void initiateWire_unresolvedIban_completesAsNonOnUsWireWithNoCredit() {
        authenticateAsFullAuthUser(42);
        willThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"))
                .given(accountServiceClient).lookupByIban(VALID_IBAN);

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT, "Jane Doe", new BigDecimal("100.00"));

        var response = externalWireService.initiateWire(42L, 1L, request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.onUsTransfer()).isFalse();
        verify(accountServiceClient, never()).credit(any(), any());
    }

    /**
     * Completes the held-wire story: the second leg deferred at initiation is finally paid out when
     * fraud review approves a wire that carries a {@code destinationAccountId}.
     */
    @Test
    @DisplayName("Approving a held on-us wire credits the destination account before completing - [MEANT TO PASS]")
    void updateFraudStatus_approvedHeldOnUsWire_creditsDestinationAndCompletes() throws Exception {
        UUID transactionId = UUID.randomUUID();
        TransactionEntity heldWire = new TransactionEntity();
        heldWire.setTransactionId(transactionId);
        heldWire.setAccountId(1L);
        heldWire.setAmount(new BigDecimal("7500.00"));
        heldWire.setStatus(TransactionStatus.PENDING_APPROVAL);
        heldWire.setDescription("External Wire to Jane Doe");
        heldWire.setDestinationAccountId(99L);
        given(transactionRepository.findById(transactionId)).willReturn(Optional.of(heldWire));
        // The credit path re-vets the recipient before releasing the money, so a wire that is
        // supposed to complete has to say who owns the destination and that they're still approved.
        given(accountServiceClient.lookupAccountOwner(99L))
                .willReturn(new AccountServiceClient.AccountOwnerResponse(55L));
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "APPROVED"));

        mockMvc.perform(patch("/api/v1/internal/transfers/{id}/fraud-status", transactionId)
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\",\"reviewerNotes\":\"looks fine\"}"))
                .andExpect(status().isOk());

        verify(accountServiceClient).credit(eq(99L), any());
        verify(transactionRepository).save(argThat(tx -> tx.getStatus() == TransactionStatus.COMPLETED));
    }

    /**
     * The control case for the test above: a held wire with no {@code destinationAccountId} is
     * genuinely external, so there is no account here to credit and no platform user to re-vet. The
     * {@code lookupAccountOwner} verify is the load-bearing one - it proves the review path does not
     * go asking account-service about a destination that does not exist.
     */
    @Test
    @DisplayName("Approving a held genuinely-external wire does not attempt to credit anything - [MEANT TO PASS]")
    void updateFraudStatus_approvedHeldExternalWire_completesWithoutCreditOrOwnerLookup() throws Exception {
        UUID transactionId = UUID.randomUUID();
        TransactionEntity heldWire = new TransactionEntity();
        heldWire.setTransactionId(transactionId);
        heldWire.setAccountId(1L);
        heldWire.setAmount(new BigDecimal("7500.00"));
        heldWire.setStatus(TransactionStatus.PENDING_APPROVAL);
        heldWire.setDescription("External Wire to Jane Doe");
        given(transactionRepository.findById(transactionId)).willReturn(Optional.of(heldWire));

        mockMvc.perform(patch("/api/v1/internal/transfers/{id}/fraud-status", transactionId)
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\",\"reviewerNotes\":\"looks fine\"}"))
                .andExpect(status().isOk());

        verify(accountServiceClient, never()).credit(any(), any());
        verify(transactionRepository).save(argThat(tx -> tx.getStatus() == TransactionStatus.COMPLETED));
        verify(accountServiceClient, never()).lookupAccountOwner(any());
    }

    // ==========================================
    // Recipient-side KYC at review time: a held wire can sit in PENDING_APPROVAL for days, and the
    // recipient's verification can be revoked while it waits. The initiation-time check in
    // ExternalWireService can't cover that, so the credit path re-checks before releasing the money.
    // ==========================================

    // A held on-us wire, ready for its second leg, aimed at account 99 owned by user 55.
    private TransactionEntity givenHeldOnUsWire(UUID transactionId) {
        TransactionEntity heldWire = new TransactionEntity();
        heldWire.setTransactionId(transactionId);
        heldWire.setAccountId(1L);
        heldWire.setAmount(new BigDecimal("7500.00"));
        heldWire.setStatus(TransactionStatus.PENDING_APPROVAL);
        heldWire.setDescription("External Wire to Jane Doe");
        heldWire.setDestinationAccountId(99L);
        given(transactionRepository.findById(transactionId)).willReturn(Optional.of(heldWire));
        return heldWire;
    }

    private void approveAsReviewer(UUID transactionId) throws Exception {
        resolveAsReviewer(transactionId, "APPROVED");
    }

    private void rejectAsReviewer(UUID transactionId) throws Exception {
        resolveAsReviewer(transactionId, "REJECTED");
    }

    private void resolveAsReviewer(UUID transactionId, String verdict) throws Exception {
        mockMvc.perform(patch("/api/v1/internal/transfers/{id}/fraud-status", transactionId)
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + verdict + "\",\"reviewerNotes\":\"looks fine\"}"))
                .andExpect(status().isOk());
    }

    /**
     * The reviewer clears the wire, but by now the recipient's verification no longer stands. The
     * money must not land, and it must not sit in PENDING_APPROVAL forever either - it goes back to
     * the sender. The description assertions are about the audit trail: the reviewer's real verdict
     * has to survive, the reversal has to be attributed to the recipient, and filing it as
     * {@code Fraud Review: REJECTED} would be a false record of a decision the reviewer never made.
     */
    @Test
    @DisplayName("Approving a held on-us wire whose recipient is no longer verified reverses it and refunds the sender - [MEANT TO FAIL]")
    void updateFraudStatus_approvedWireWhoseRecipientLostVerification_reversesAndRefundsSender() throws Exception {
        UUID transactionId = UUID.randomUUID();
        givenHeldOnUsWire(transactionId);
        given(accountServiceClient.lookupAccountOwner(99L))
                .willReturn(new AccountServiceClient.AccountOwnerResponse(55L));
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "PENDING_VERIFICATION"));

        approveAsReviewer(transactionId);

        // Destination never sees the money; the sender gets their hold back.
        verify(accountServiceClient, never()).credit(eq(99L), any());
        verify(accountServiceClient).credit(eq(1L), any());
        verify(transactionRepository).save(argThat(tx ->
                tx.getStatus() == TransactionStatus.REJECTED
                        && tx.getDescription().contains("Fraud Review: APPROVED")
                        && tx.getDescription().contains("recipient cannot receive funds")
                        && !tx.getDescription().contains("Fraud Review: REJECTED")));
    }

    /**
     * The over-blocking guard for the reversal above: a recipient who is still approved at review
     * time is credited exactly as before, and the "never credit the sender" verify is what proves no
     * spurious refund was issued alongside it.
     */
    @Test
    @DisplayName("Approving a held on-us wire whose recipient is still verified credits the destination as before - [MEANT TO PASS]")
    void updateFraudStatus_approvedWireWhoseRecipientStillVerified_creditsDestinationAndNotSender() throws Exception {
        UUID transactionId = UUID.randomUUID();
        givenHeldOnUsWire(transactionId);
        given(accountServiceClient.lookupAccountOwner(99L))
                .willReturn(new AccountServiceClient.AccountOwnerResponse(55L));
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "APPROVED"));

        approveAsReviewer(transactionId);

        verify(accountServiceClient).credit(eq(99L), any());
        verify(accountServiceClient, never()).credit(eq(1L), any());
        verify(transactionRepository).save(argThat(tx -> tx.getStatus() == TransactionStatus.COMPLETED));
    }

    /**
     * If we cannot establish who is being paid, we cannot establish that they may be paid - so a
     * failed owner lookup fails closed exactly like an outright unverified recipient, rather than
     * crediting on a guess or leaving the sender's money stranded in PENDING_APPROVAL.
     */
    @Test
    @DisplayName("Approving a held on-us wire whose owner lookup fails reverses it rather than crediting blind - [MEANT TO FAIL]")
    void updateFraudStatus_approvedWireWithFailingOwnerLookup_reversesRatherThanCreditingBlind() throws Exception {
        UUID transactionId = UUID.randomUUID();
        givenHeldOnUsWire(transactionId);
        // Covers both halves of the failure case: the ErrorDecoder turns account-service's 404 into
        // exactly this, and an unreachable account-service surfaces as a RuntimeException too.
        willThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"))
                .given(accountServiceClient).lookupAccountOwner(99L);

        approveAsReviewer(transactionId);

        verify(accountServiceClient, never()).credit(eq(99L), any());
        verify(accountServiceClient).credit(eq(1L), any());
        verify(transactionRepository).save(argThat(tx -> tx.getStatus() == TransactionStatus.REJECTED));
    }

    // ==========================================
    // Recipient-side KYC at initiation: an APPROVED sender still cannot push money into the account
    // of someone whose own verification hasn't cleared. @RequiresKyc only ever vets the caller, so
    // these cover the other half - the user on the receiving end - across all three transfer paths.
    // ==========================================

    private static final String RECIPIENT_ACCOUNT_NUMBER = "1234567890";

    // Recipient user 55 owning account 99 - the same shape account-service's by-number lookup returns.
    private void givenRecipientExists() {
        given(accountServiceClient.lookupByAccountNumber(RECIPIENT_ACCOUNT_NUMBER))
                .willReturn(new AccountServiceClient.RecipientLookupResponse(
                        99L, 55L, "CHECKING", "******7890", "ACTIVE"));
    }

    /**
     * The three {@code never()} verifies together are the point: there is no half-transfer to unwind
     * once {@code transferToRecipient} has run, so the refusal has to land before account-service is
     * asked to move anything at all - by any of its three entry points.
     */
    @Test
    @DisplayName("Transfer to a PENDING_VERIFICATION recipient is rejected before any debit - [MEANT TO FAIL]")
    void executeTransferToRecipient_recipientKycPendingVerification_throwsKycRequiredBeforeAnyDebit() {
        authenticateAsFullAuthUser(42);
        givenRecipientExists();
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "PENDING_VERIFICATION"));

        assertThatThrownBy(() -> transferService.executeTransferToRecipient(
                42L, 1L, RECIPIENT_ACCOUNT_NUMBER, new BigDecimal("100.00")))
                .isInstanceOf(KycEnforcementAspect.KycRequiredException.class)
                .hasMessageContaining("identity verification isn't complete");

        verify(accountServiceClient, never()).transferToRecipient(any());
        verify(accountServiceClient, never()).transfer(any());
        verify(accountServiceClient, never()).debit(any(), any());
    }

    /**
     * Same gate, harsher status. The message must stay generic either way: the sender is never told
     * which of the two verdicts the recipient carries, since that is the recipient's business.
     */
    @Test
    @DisplayName("Transfer to a REJECTED recipient is rejected without naming their status - [MEANT TO FAIL]")
    void executeTransferToRecipient_recipientKycRejected_throwsKycRequiredWithoutNamingTheStatus() {
        authenticateAsFullAuthUser(42);
        givenRecipientExists();
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "REJECTED"));

        assertThatThrownBy(() -> transferService.executeTransferToRecipient(
                42L, 1L, RECIPIENT_ACCOUNT_NUMBER, new BigDecimal("100.00")))
                .isInstanceOf(KycEnforcementAspect.KycRequiredException.class)
                .hasMessageContaining("identity verification isn't complete")
                .hasMessageNotContaining("REJECTED");

        verify(accountServiceClient, never()).transferToRecipient(any());
    }

    @Test
    @DisplayName("Transfer to an APPROVED recipient completes and delegates to account-service - [MEANT TO PASS]")
    void executeTransferToRecipient_recipientKycApproved_completesAndDelegatesToAccountService() {
        authenticateAsFullAuthUser(42);
        givenRecipientExists();
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "APPROVED"));

        var response = transferService.executeTransferToRecipient(
                42L, 1L, RECIPIENT_ACCOUNT_NUMBER, new BigDecimal("100.00"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(accountServiceClient).transferToRecipient(new AccountServiceClient.TransferRequest(
                42L, 1L, 99L, new BigDecimal("100.00")));
    }

    /**
     * An own-accounts transfer has no second person in it, so it must not pay for a second
     * profile-service round trip. The {@code verifyNoMoreInteractions} is doing the real work here:
     * on its own the single {@code getKycStatus(42L)} verify would still pass if a recipient lookup
     * had also fired.
     */
    @Test
    @DisplayName("Own-account transfer still works and consults only the caller's KYC status - [MEANT TO PASS]")
    void executeTransfer_ownAccountsOnly_completesAfterCheckingOnlyTheCallerKyc() {
        authenticateAsFullAuthUser(42);

        var response = transferService.executeTransfer(42L, 1L, 2L, new BigDecimal("100.00"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(accountServiceClient).transfer(new AccountServiceClient.TransferRequest(42L, 1L, 2L, new BigDecimal("100.00")));
        verify(profileServiceClient).getKycStatus(42L);
        verifyNoMoreInteractions(profileServiceClient);
    }

    /**
     * The wire path's version of the recipient gate: an IBAN that resolves here means a real platform
     * user is about to be credited, so they get vetted, and nothing is reserved when they fail.
     */
    @Test
    @DisplayName("On-us wire to an unverified account holder is blocked before funds are reserved - [MEANT TO FAIL]")
    void initiateWire_onUsIbanHeldByUnverifiedUser_throwsKycRequiredBeforeFundsReserved() {
        authenticateAsFullAuthUser(42);
        given(accountServiceClient.lookupByIban(VALID_IBAN))
                .willReturn(new AccountServiceClient.AccountLookupResponse(99L, 55L, "CHECKING", "ACTIVE", VALID_SWIFT));
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "PENDING_VERIFICATION"));

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT, "Jane Doe", new BigDecimal("100.00"));

        assertThatThrownBy(() -> externalWireService.initiateWire(42L, 1L, request))
                .isInstanceOf(KycEnforcementAspect.KycRequiredException.class)
                .hasMessageContaining("identity verification isn't complete");

        verify(accountServiceClient, never()).debit(any(), any());
        verify(accountServiceClient, never()).credit(any(), any());
        verify(transactionRepository, never()).save(any());
    }

    /**
     * The boundary the recipient check must never cross: a genuinely external IBAN has no platform
     * user behind it, and another bank's customer is not ours to verify. The
     * {@code verifyNoMoreInteractions} proves the sender's status was the only one ever looked up.
     */
    @Test
    @DisplayName("Wire to an IBAN that doesn't resolve here is still allowed with no recipient KYC lookup - [MEANT TO PASS]")
    void initiateWire_unresolvedIban_completesWithNoRecipientKycLookup() {
        authenticateAsFullAuthUser(42);
        willThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"))
                .given(accountServiceClient).lookupByIban(VALID_IBAN);

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT, "Jane Doe", new BigDecimal("100.00"));

        var response = externalWireService.initiateWire(42L, 1L, request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.onUsTransfer()).isFalse();
        verify(accountServiceClient).debit(eq(1L), any());
        verify(profileServiceClient).getKycStatus(42L);
        verifyNoMoreInteractions(profileServiceClient);
    }

    /**
     * The preview is where the frontend warns, not where it blocks: an unverified recipient still
     * answers 200 so the sender can see who they would be paying, just flagged as unverified.
     */
    @Test
    @DisplayName("Recipient preview reports verified=false instead of failing for an unverified recipient - [MEANT TO PASS]")
    void previewRecipient_unverifiedRecipient_returns200WithVerifiedFalse() throws Exception {
        givenRecipientExists();
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "PENDING_VERIFICATION"));
        given(authServiceClient.getDisplayName(55L))
                .willReturn(new AuthServiceClient.DisplayNameResponse(55L, "Jane Doe"));

        mockMvc.perform(get("/api/v1/transfers/recipients/{accountNumber}", RECIPIENT_ACCOUNT_NUMBER)
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Jane Doe"))
                .andExpect(jsonPath("$.verified").value(false));
    }

    // ==========================================
    // Transfer History (GET /api/v1/transfers) - powers the frontend's History page
    // ==========================================

    /**
     * transaction-service does not know which accounts a user owns; account-service does. The
     * {@code verify} proves the query was scoped by that resolved list rather than by anything the
     * caller supplied.
     */
    @Test
    @DisplayName("Transfer history resolves the caller's account IDs via account-service and queries by them - [MEANT TO PASS]")
    void getTransferHistory_authenticatedCaller_returns200AfterResolvingOwnedAccountIds() throws Exception {
        given(accountServiceClient.getAccountIdsByUser(42L)).willReturn(List.of(1L, 2L));
        given(transactionRepository.findByAccountIdInWithFilters(eq(List.of(1L, 2L)), isNull(), isNull(), isNull(), any()))
                .willReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/transfers")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L))))
                .andExpect(status().isOk());

        verify(accountServiceClient).getAccountIdsByUser(42L);
    }

    @Test
    @DisplayName("Transfer history narrowed to an accountId not owned by the caller is forbidden - [MEANT TO FAIL]")
    void getTransferHistory_accountIdFilterNotOwnedByCaller_returns403() throws Exception {
        given(accountServiceClient.getAccountIdsByUser(42L)).willReturn(List.of(1L));

        mockMvc.perform(get("/api/v1/transfers")
                .param("accountId", "999")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Transfer history is rejected for an unauthenticated caller - [MEANT TO FAIL]")
    void getTransferHistory_noAuthentication_returns4xx() throws Exception {
        mockMvc.perform(get("/api/v1/transfers"))
                .andExpect(status().is4xxClientError());
    }

    // ==========================================
    // Shared-secret gate on /api/v1/internal/**. The fraud-status endpoint approves or reverses a
    // held wire, so anyone who could reach it could release or claw back real money. Until now the
    // only thing standing in the way was the k8s ingress declining to route the prefix.
    // ==========================================

    /**
     * The whole point of the filter: no secret, no entry - even though the authorize layer marks this
     * path {@code permitAll} and the request is otherwise perfectly well formed. The two
     * {@code never()} verifies prove the request was turned away in the filter, before any lookup or
     * money movement could run.
     */
    @Test
    @DisplayName("Block 13: Fraud-status endpoint with no X-Internal-Token is rejected with 401 - [MEANT TO FAIL]")
    void updateFraudStatus_missingInternalTokenHeader_returns401AndRunsNothingDownstream() throws Exception {
        UUID transactionId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/internal/transfers/{id}/fraud-status", transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\",\"reviewerNotes\":\"looks fine\"}"))
                .andExpect(status().isUnauthorized())
                // Both keys, same text - the body shape every service answers this with
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());

        verify(transactionRepository, never()).findById(any());
        verify(accountServiceClient, never()).credit(any(), any());
    }

    /**
     * A wrong secret is answered exactly like a missing one, and the message names neither the header
     * nor the property - a caller probing this endpoint learns nothing to go looking for.
     */
    @Test
    @DisplayName("Block 14: Fraud-status endpoint with a wrong X-Internal-Token is rejected with 401 - [MEANT TO FAIL]")
    void updateFraudStatus_wrongInternalToken_returns401WithoutNamingHeaderOrProperty() throws Exception {
        UUID transactionId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/internal/transfers/{id}/fraud-status", transactionId)
                .header(INTERNAL_TOKEN_HEADER, "not-the-internal-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\",\"reviewerNotes\":\"looks fine\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(INTERNAL_TOKEN_HEADER))))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal-token"))));

        verify(transactionRepository, never()).findById(any());
        verify(accountServiceClient, never()).credit(any(), any());
    }

    /**
     * The over-blocking guard: the correct secret still resolves a wire exactly as before. No JWT on
     * this one on purpose - a service-to-service caller has no end-user token to present, and the
     * shared secret is now the whole of what authorizes the call.
     */
    @Test
    @DisplayName("Block 15: Fraud-status endpoint with the correct X-Internal-Token resolves the wire as before - [MEANT TO PASS]")
    void updateFraudStatus_correctInternalTokenAndNoJwt_resolvesWireAsBefore() throws Exception {
        UUID transactionId = UUID.randomUUID();
        givenHeldOnUsWire(transactionId);
        given(accountServiceClient.lookupAccountOwner(99L))
                .willReturn(new AccountServiceClient.AccountOwnerResponse(55L));
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "APPROVED"));

        mockMvc.perform(patch("/api/v1/internal/transfers/{id}/fraud-status", transactionId)
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\",\"reviewerNotes\":\"looks fine\"}"))
                .andExpect(status().isOk());

        verify(accountServiceClient).credit(eq(99L), any());
        verify(transactionRepository).save(argThat(tx -> tx.getStatus() == TransactionStatus.COMPLETED));
    }

    /**
     * The other side of the gate, and the regression that would hurt most if the filter's path check
     * were wrong: a customer request carries no {@code X-Internal-Token} and must never be asked for
     * one.
     */
    @Test
    @DisplayName("Block 16: Customer-facing JWT endpoint still works with no X-Internal-Token header - [MEANT TO PASS]")
    void getTransferHistory_customerJwtWithoutInternalToken_returns200() throws Exception {
        given(accountServiceClient.getAccountIdsByUser(42L)).willReturn(List.of(1L, 2L));
        given(transactionRepository.findByAccountIdInWithFilters(eq(List.of(1L, 2L)), isNull(), isNull(), isNull(), any()))
                .willReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/transfers")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L))))
                .andExpect(status().isOk());
    }

    /**
     * The outbound half of the shared-secret contract. All three Feign clients in this module call
     * nothing but other services' {@code /api/v1/internal/} endpoints, so the moment those services
     * enforce their own filters a missing header on this side is every transfer failing, not a
     * degraded feature. Every client here is a {@code @MockBean}, so a bare {@code RequestTemplate}
     * put through the real interceptor is the only way to observe the header being attached.
     */
    @Test
    @DisplayName("Block 17: Outbound Feign interceptor attaches the shared secret to every request - [MEANT TO PASS]")
    void apply_bareRequestTemplate_attachesConfiguredInternalTokenHeader() {
        RequestTemplate template = new RequestTemplate();

        internalTokenRequestInterceptor.apply(template);

        // The configured value, not the hardcoded dev default - @TestPropertySource overrides it.
        assertThat(template.headers().get(INTERNAL_TOKEN_HEADER)).containsExactly(INTERNAL_TOKEN);
    }

    // ==========================================
    // Idempotency keys on the fraud-resolution money movements. The remote credit happens inside a
    // local @Transactional method, so it can succeed and the commit that records it still fail -
    // leaving the wire PENDING_APPROVAL and the resolution re-runnable against real money.
    // ==========================================

    /**
     * The destination credit and the sender refund are two DIFFERENT effects on ONE wire. Keying both
     * off the transaction id alone would make whichever ran second look like a duplicate of the
     * first, and account-service would silently swallow it - money that should have moved quietly not
     * moving, with a success response either way.
     * <p>
     * Both resolutions are driven against the SAME transaction id deliberately: keys taken from two
     * different wires would differ no matter how carelessly they were built, so only one id can
     * actually catch the mistake. That makes this test order-dependent by construction - the approval
     * has to run first, and the entity is manually put back into PENDING_APPROVAL between the two
     * calls, which is not a sequence a real reviewer produces.
     */
    @Test
    @DisplayName("Block 18: Destination credit and sender refund on one wire carry DIFFERENT idempotency keys - [MEANT TO FAIL]")
    void updateFraudStatus_sameWireCreditedThenRefunded_usesDistinctIdempotencyKeys() throws Exception {
        UUID transactionId = UUID.randomUUID();
        TransactionEntity heldWire = givenHeldOnUsWire(transactionId);
        given(accountServiceClient.lookupAccountOwner(99L))
                .willReturn(new AccountServiceClient.AccountOwnerResponse(55L));
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "APPROVED"));

        approveAsReviewer(transactionId);
        heldWire.setStatus(TransactionStatus.PENDING_APPROVAL);
        rejectAsReviewer(transactionId);

        ArgumentCaptor<AccountServiceClient.CreditRequest> destinationCredit =
                ArgumentCaptor.forClass(AccountServiceClient.CreditRequest.class);
        verify(accountServiceClient).credit(eq(99L), destinationCredit.capture());

        ArgumentCaptor<AccountServiceClient.CreditRequest> senderRefund =
                ArgumentCaptor.forClass(AccountServiceClient.CreditRequest.class);
        verify(accountServiceClient).credit(eq(1L), senderRefund.capture());

        String destinationKey = destinationCredit.getValue().idempotencyKey();
        String refundKey = senderRefund.getValue().idempotencyKey();

        // Stable: both derived from the wire itself, so a retry of either resolution reproduces the
        // same key rather than reading as a fresh effect.
        assertThat(destinationKey).isNotNull().contains(transactionId.toString());
        assertThat(refundKey).isNotNull().contains(transactionId.toString());
        // ...and distinct, so neither is ever mistaken for the other.
        assertThat(destinationKey).isNotEqualTo(refundKey);
    }

    // ==========================================
    // Wire initiation when account-service can't answer. The IBAN lookup decides whether a wire is
    // on-us, and a connection failure never reaches FeignErrorConfig's ErrorDecoder at all - there
    // is no response to decode - so it used to propagate raw and surface as a 500.
    // ==========================================

    @Test
    @DisplayName("Block 19: Unreachable account-service at wire initiation returns HTTP 503 and saves no wire - [MEANT TO FAIL]")
    void executeExternalWire_accountServiceUnreachableAtInitiation_returns503AndSavesNoWire() throws Exception {
        // What Feign throws when the connection can't be made at all, rather than an HTTP error body.
        willThrow(new RuntimeException("Connection refused: connect"))
                .given(accountServiceClient).lookupByIban(VALID_IBAN);
        String payload = objectMapper.writeValueAsString(new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT, "Jane Doe", new BigDecimal("100.00")));

        mockMvc.perform(post("/api/v1/transfers/external")
                .param("fromAccountId", "1")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("couldn't complete your wire transfer")))
                // account-service uses "message"; both keys carry the same text so either client helper reads it
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("try again in a moment")))
                // and it must not name an internal service to the customer
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("account-service"))));

        // Nothing was reserved and no wire row exists to reconcile later - the failure is clean.
        verify(accountServiceClient, never()).debit(any(), any());
        verify(transactionRepository, never()).save(any());
    }

    /**
     * The over-blocking guard for the 503 above, and the reason its try/catch is scoped to the lookup
     * call alone. {@code resolveOnUsDestination} also runs the recipient-KYC check, which throws for
     * an unverified recipient - catching {@code RuntimeException} around the whole method body would
     * rewrite that 403 verdict as a 503 outage and disable the recipient-KYC rule while still looking
     * like it ran. account-service answers here perfectly well; it is the recipient who is the
     * problem.
     */
    @Test
    @DisplayName("Block 20: Unverified recipient at wire initiation still returns 403, never 503 - [MEANT TO FAIL]")
    void executeExternalWire_unverifiedOnUsRecipient_returns403NotAnOutage503() throws Exception {
        given(accountServiceClient.lookupByIban(VALID_IBAN))
                .willReturn(new AccountServiceClient.AccountLookupResponse(99L, 55L, "CHECKING", "ACTIVE", VALID_SWIFT));
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "PENDING_VERIFICATION"));
        String payload = objectMapper.writeValueAsString(new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT, "Jane Doe", new BigDecimal("100.00")));

        mockMvc.perform(post("/api/v1/transfers/external")
                .param("fromAccountId", "1")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("identity verification isn't complete")))
                // the specific regression: the recipient rule must not come back dressed as an outage
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("try again in a moment"))));

        verify(accountServiceClient, never()).debit(any(), any());
        verify(transactionRepository, never()).save(any());
    }
}
