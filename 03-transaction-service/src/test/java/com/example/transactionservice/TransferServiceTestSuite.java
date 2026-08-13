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

    // The shared service-to-service secret, identical in all five services: header name, property
    // name, and the fact that every /api/v1/internal/ request must carry it. Spelled out again here
    // rather than referenced from the annotation above, which only accepts compile-time literals.
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

    // Only the recipient-preview tests touch this one, but it has to be mocked rather than left as
    // the real Feign client - the preview resolves a display name, and an unmocked client would try
    // a live call to auth-service on port 8081.
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
    // KycEnforcementAspect resolves the caller from SecurityContextHolder, not from the
    // userId method parameter, so tests calling the service directly (not through MockMvc)
    // need a real Jwt-shaped Authentication installed for @RequiresKyc to reach this stub at all.
    @BeforeEach
    void setUp() {
        given(profileServiceClient.getKycStatus(42L)).willReturn(Map.of("status", "APPROVED"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // KycEnforcementAspect casts the SecurityContext principal to a Jwt (it reads the caller's
    // "userId" claim, not the JWT subject) - this installs a real Jwt-shaped Authentication so
    // direct service-layer calls (bypassing MockMvc/@WithMockUser entirely) hit the aspect the same
    // way a real authenticated request would.
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

    // checking that trying to move more money than is actually available gets rejected -
    // account-service's InternalAccountController is where that check now actually runs, so this
    // test simulates its rejection by having the Feign client throw the same exception it would
    // translate a 400 "INSUFFICIENT_FUNDS" response into (see FeignErrorConfig)
    @Test
    @DisplayName("Block 1: Insufficient funds rejects transfer with INSUFFICIENT_FUNDS - [MEANT TO FAIL]")
    void testBlock1_ExecuteTransfer_InsufficientFunds_ThrowsBadRequest() {
        authenticateAsFullAuthUser(42);
        willThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_FUNDS"))
                .given(accountServiceClient).transfer(any());

        assertThatThrownBy(() ->
                transferService.executeTransfer(42L, 1L, 2L, new BigDecimal("5000.00")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INSUFFICIENT_FUNDS");
    }

    // making sure a user cannot transfer money into or out of an account that is not actually
    // theirs - again, account-service enforces this now; simulate its 403 rejection here
    @Test
    @DisplayName("Block 2: Transfer between accounts not owned by the caller is forbidden - [MEANT TO FAIL]")
    void testBlock2_ExecuteTransfer_OwnershipMismatch_ThrowsForbidden() {
        authenticateAsFullAuthUser(42);
        willThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Both accounts must belong to the authenticated user"))
                .given(accountServiceClient).transfer(any());

        assertThatThrownBy(() ->
                transferService.executeTransfer(42L, 1L, 2L, new BigDecimal("100.00")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Both accounts must belong to the authenticated user");
    }

    // the pessimistic locking itself now happens inside account-service (verified there via the
    // docker-compose end-to-end check), so from transaction-service's side the equivalent
    // guarantee to verify is that the transfer is delegated there with exactly the right request -
    // it never mutates any balance locally itself
    @Test
    @DisplayName("Block 3: Transfer delegates the balance mutation to account-service with the correct request - [MEANT TO PASS]")
    void testBlock3_ExecuteTransfer_DelegatesToAccountService() {
        authenticateAsFullAuthUser(42);

        transferService.executeTransfer(42L, 1L, 2L, new BigDecimal("100.00"));

        verify(accountServiceClient).transfer(new AccountServiceClient.TransferRequest(42L, 1L, 2L, new BigDecimal("100.00")));
    }

    // full end to end test for a normal successful internal transfer, going through the real http endpoint
    // deliberately not wrapping this test itself in a transaction, since the real code relies on
    // @transactionaleventlistener(phase = after_commit), and that would never fire if this test
    // wrapped everything in a transaction that just gets rolled back at the end
    // expect status ok with a transaction id and a completed status in the response, the transfer
    // delegated to account-service with the right request, and a fundstransferredevent published
    @Test
    @DisplayName("Final Block: Successful internal transfer commits balances, returns confirmation ID, and publishes FundsTransferredEvent to Kafka AFTER commit - [MEANT TO PASS]")
    void testFinalAC_InternalTransfer_SuccessCommitsAndPublishesEvent() throws Exception {
        // NOTE: deliberately NOT @Transactional at the test level - the production code relies on
        // @TransactionalEventListener(phase = AFTER_COMMIT), which never fires if the test itself
        // wraps the call in a transaction that gets rolled back.
        InternalTransferRequestDto request = new InternalTransferRequestDto(1L, 2L, new BigDecimal("100.00"));

        // Unlike the other tests in this suite, this one actually reaches TransferController's
        // extractUserIdFromAuth(), which casts the principal to a Jwt - @WithMockUser's plain
        // User principal would fail that cast, so this needs a real Jwt-shaped mock principal.
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

    // making sure an obviously malformed iban gets caught before it ever reaches the service layer
    // build a raw json payload with a completely bogus iban string, not even close to the real format
    // post that to the external wire endpoint
    // expect a plain 400 bad request, this is jakarta validation's @pattern annotation on the dto
    // catching the bad shape before any real business logic even runs
    @Test
    @DisplayName("Block 4: Structurally invalid IBAN/SWIFT rejected before reaching the service - [MEANT TO FAIL]")
    @WithMockUser(username = "42", authorities = {"SCOPE_FULL_AUTH"})
    void testBlock4_ExternalWire_MalformedIban_ReturnsBadRequest() throws Exception {
        String payload = """
                {"iban":"NOT_AN_IBAN","swiftCode":"DEUTDEFF","beneficiaryName":"John Smith","amount":100.00}
                """;

        mockMvc.perform(post("/api/v1/transfers/external")
                .param("fromAccountId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest());
    }

    // one level deeper than the last test, this iban looks correctly formatted but the checksum is wrong
    // build a request with an iban that is one digit off from the real valid checksum iban constant,
    // which fails the actual mod 97 checksum math even though a simple regex would let it through -
    // this is caught by validateFormat() before account-service is ever called
    // call initiatewire directly instead of going through mockmvc this time
    // expect a responsestatusexception mentioning invalid iban or swift code format
    @Test
    @DisplayName("Block 5: Structurally valid but checksum-invalid IBAN rejected by IbanSwiftValidator - [MEANT TO FAIL]")
    void testBlock5_ExternalWire_ChecksumInvalidIban_ThrowsBadRequest() {
        authenticateAsFullAuthUser(42);

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                "GB30NWBK60161331926819", // one digit off from the valid checksum IBAN -> fails MOD 97
                VALID_SWIFT, "John Smith", new BigDecimal("100.00"));

        assertThatThrownBy(() -> externalWireService.initiateWire(42L, 1L, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid IBAN or SWIFT code format");
    }

    // making sure an external wire for more money than is available gets rejected up front -
    // account-service's debit endpoint enforces this now; simulate its rejection here
    // build a wire request using the known valid iban and swift constants but asking for way more, 5000.01
    // call initiatewire directly
    // expect a responsestatusexception mentioning insufficient_funds, same style error as internal transfers
    @Test
    @DisplayName("Block 6: Insufficient funds rejects external wire before reserving funds - [MEANT TO FAIL]")
    void testBlock6_ExternalWire_InsufficientFunds_ThrowsBadRequest() {
        authenticateAsFullAuthUser(42);
        willThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_FUNDS"))
                .given(accountServiceClient).debit(eq(1L), any());

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT, "John Smith", new BigDecimal("5000.01"));

        assertThatThrownBy(() -> externalWireService.initiateWire(42L, 1L, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INSUFFICIENT_FUNDS");
    }

    // checking the exact boundary of the fraud review threshold, right at five thousand dollars
    // build a wire request for exactly five thousand dollars, the threshold value itself
    // call initiatewire directly (account-service's debit call succeeds by default - a mocked void
    // method does nothing unless stubbed to throw)
    // since the rule is strictly greater than five thousand, this amount should complete right away
    // expect the response status to say completed and confirm no fraud review kafka event went out
    @Test
    @DisplayName("Block 7: Wire at or below $5000 completes immediately without a fraud event - [MEANT TO PASS]")
    void testBlock7_ExternalWire_AtThreshold_CompletesWithoutFraudEvent() {
        authenticateAsFullAuthUser(42);

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT, "John Smith", new BigDecimal("5000.00"));

        var response = externalWireService.initiateWire(42L, 1L, request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(largeTransferKafkaTemplate, never()).send(any(), any(), any());
    }

    // the flip side of the last test, going over the five thousand dollar threshold this time
    // build a wire request for seventy five hundred dollars, comfortably over the threshold
    // call initiatewire directly
    // expect the response status to say pending_approval instead of completed
    // confirm the debit was delegated to account-service with the right amount, a wire_transactions
    // record got saved locally, and a largetransferrequestedevent went out to the fraud review topic
    @Test
    @DisplayName("Final Block: Wire over $5000 is pre-reserved, marked PENDING_APPROVAL, and publishes LargeTransferRequestedEvent - [MEANT TO PASS]")
    void testFinalAC_ExternalWire_OverThreshold_PendingApprovalAndFraudEvent() {
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

    // making sure a pre auth session, meaning 2fa was never finished, cannot move money at all
    // withmockuser only grants scope_pre_auth here instead of the full auth scope other tests use
    // build a small ten dollar transfer request and post it to the internal transfer endpoint
    // expect a 403 forbidden since moving real money requires a fully authenticated session
    @Test
    @WithMockUser(username = "42", authorities = {"SCOPE_PRE_AUTH"})
    @DisplayName("Block 8: Pre-Auth (partial 2FA) token denied on internal transfer endpoint - [MEANT TO FAIL]")
    void testBlock8_InternalTransfer_PreAuthTokenDenied() throws Exception {
        InternalTransferRequestDto request = new InternalTransferRequestDto(1L, 2L, new BigDecimal("10.00"));

        mockMvc.perform(post("/api/v1/transfers/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // same idea but no logged in user at all this time, not even a partial session
    // no withmockuser annotation here on purpose
    // post the same small transfer request with no authentication attached
    // expect some flavor of 4xx client error, confirming anonymous requests never get near real money
    @Test
    @DisplayName("Block 9: Unauthenticated request denied on internal transfer endpoint - [MEANT TO FAIL]")
    void testBlock9_InternalTransfer_UnauthenticatedDenied() throws Exception {
        InternalTransferRequestDto request = new InternalTransferRequestDto(1L, 2L, new BigDecimal("10.00"));

        mockMvc.perform(post("/api/v1/transfers/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError());
    }

    // checking that kyc enforcement actually blocks a transfer for a user who is not approved yet
    // override the kyc stub to say pending_verification, call executetransfer directly
    // expect it to throw kycrequiredexception, the specific exception the aop aspect throws,
    // and its message should mention the pending_verification status so its clear why it was blocked
    // last, confirm account-service never even got called, no money moved before the kyc check ran
    @Test
    @DisplayName("Block 10: Non-APPROVED KYC status blocks an internal transfer - [MEANT TO FAIL]")
    void testBlock10_NonApprovedKyc_BlocksInternalTransfer() {
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

    // same kyc gating check but this time on the external wire path instead of internal transfers
    // override the kyc stub to say rejected this time, a harsher status
    // build a normal, otherwise valid wire request
    // call initiatewire directly
    // expect kycrequiredexception with a message mentioning rejected
    // and confirm neither account-service nor the local transaction record got touched
    @Test
    @DisplayName("Final Block: Non-APPROVED KYC status blocks an external wire before any funds move - [MEANT TO FAIL]")
    void testFinalAC_NonApprovedKyc_BlocksExternalWire() {
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

    // GlobalExceptionHandler is what turns KycRequiredException into a real 403 over HTTP - the
    // two tests above call the service directly, bypassing the RestControllerAdvice entirely, so
    // this one specifically goes through MockMvc to prove a real request gets 403, not the
    // unhandled 500 KycEnforcementAspect's own Javadoc used to (incorrectly) promise.
    @Test
    @DisplayName("Block 11: Non-APPROVED KYC status returns HTTP 403 (not an unhandled 500) - [MEANT TO FAIL]")
    void testBlock11_NonApprovedKyc_ReturnsHttp403() throws Exception {
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

    // the other half of Block 11: not "we asked and the answer was no" but "we couldn't ask at all".
    // The gate still fails closed - an unreachable profile-service is never read as approval and no
    // money moves - but the caller is told it's a retryable outage (503) rather than an unhandled
    // crash (500, what a bare RuntimeException/Feign failure used to produce) or a verdict on them (403).
    @Test
    @DisplayName("Block 12: Unreachable profile-service returns HTTP 503 and moves no money - [MEANT TO FAIL]")
    void testBlock12_ProfileServiceUnreachable_Returns503AndMovesNoMoney() throws Exception {
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

    // an on-us wire that clears the fraud threshold immediately should complete both legs right
    // away - debit the sender via account-service as usual, but also credit the resolved
    // destination account, and report onUsTransfer=true so the frontend can say so
    @Test
    @DisplayName("On-us wire under threshold resolves via IBAN and credits the destination immediately - [MEANT TO PASS]")
    void testOnUsWire_UnderThreshold_CreditsDestinationImmediately() {
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

    // The IBAN and the BIC on one wire are supposed to name the same bank. isValidIban runs a real
    // mod-97 checksum so a wrong IBAN is caught, but a BIC has no check digit at all - any
    // well-formed string passed, and the destination was resolved from the IBAN alone, so a wire
    // addressed to some other bank still landed in one of our accounts.
    @Test
    @DisplayName("On-us wire with a BIC belonging to a different bank is rejected - [MEANT TO FAIL]")
    void testOnUsWire_SwiftCodeDoesNotMatchIbanHolder_Rejected() {
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

    // The pairing is checked before the recipient's KYC status, so a sender who typed the wrong bank
    // is told that - rather than being handed a 403 that describes the recipient and leaks whether
    // that person is verified to someone who addressed the wire incorrectly.
    @Test
    @DisplayName("A mismatched BIC is reported as such, not as a recipient KYC failure - [MEANT TO FAIL]")
    void testOnUsWire_MismatchedSwiftTakesPrecedenceOverRecipientKyc() {
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

    // Case and stray whitespace are not a different bank, so the comparison itself must not treat
    // them as one. Note this exercises the service directly: over HTTP, ExternalWireRequestDto's
    // @Pattern already requires an upper-case BIC and rejects "xbusus31" with 400 before this code
    // is reached. That upper-case-only contract predates this check and is unchanged - the
    // normalization here exists so the comparison stays correct on its own terms rather than
    // silently depending on a caller having upper-cased first.
    @Test
    @DisplayName("On-us BIC comparison ignores case and surrounding whitespace - [MEANT TO PASS]")
    void testOnUsWire_SwiftCodeMatchesIgnoringCase_Allowed() {
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

    // A wire to a bank that genuinely isn't us keeps working: there is no directory to check the
    // pairing against, and refusing a correct BIC we simply cannot verify would be worse.
    @Test
    @DisplayName("Genuinely external wire is unaffected by the BIC pairing rule - [MEANT TO PASS]")
    void testExternalWire_UnresolvedIban_SkipsSwiftPairingCheck() {
        authenticateAsFullAuthUser(42);
        given(accountServiceClient.lookupByIban(VALID_IBAN))
                .willThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, "DEUTDEFX", "Jane Doe", new BigDecimal("100.00"));

        var response = externalWireService.initiateWire(42L, 1L, request);

        assertThat(response.onUsTransfer()).isFalse();
        verify(accountServiceClient).debit(eq(1L), any());
    }

    // the flip side: an on-us wire over the threshold should still only hold funds (debit-only) at
    // initiation, exactly like a genuinely external wire does - the destination doesn't get its
    // half of the transfer until fraud review approves it (see the next test)
    @Test
    @DisplayName("On-us wire over threshold holds funds without crediting the destination yet - [MEANT TO PASS]")
    void testOnUsWire_OverThreshold_HoldsWithoutCreditingYet() {
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

    // an IBAN that doesn't belong to any account here (account-service returns 404, translated by
    // FeignErrorConfig into a 404 ResponseStatusException) must fall back to today's simulated
    // external behavior exactly - the regression guard that this feature doesn't change existing wires
    @Test
    @DisplayName("Wire with an unresolved IBAN stays a genuinely external, non-on-us wire - [MEANT TO PASS]")
    void testExternalWire_UnresolvedIban_IsNotOnUs() {
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

    // completing the held-wire story from testOnUsWire_OverThreshold above: once fraud review
    // approves a held wire that has a destinationAccountId, the destination should finally get
    // credited as part of finalizing it - this is the second leg that was deferred at initiation
    @Test
    @DisplayName("Approving a held on-us wire credits the destination account before completing - [MEANT TO PASS]")
    void testFraudApproval_HeldOnUsWire_CreditsDestinationOnApproval() throws Exception {
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

    // and the control case: approving a genuinely external held wire (no destinationAccountId) must
    // NOT call credit at all - there's no real destination account here to credit, exactly today's behavior
    @Test
    @DisplayName("Approving a held genuinely-external wire does not attempt to credit anything - [MEANT TO PASS]")
    void testFraudApproval_HeldExternalWire_DoesNotCreditAnything() throws Exception {
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
        // Nothing to re-vet either: there is no platform user behind a genuinely external wire, so
        // the review path must not go asking account-service who owns a destination that isn't there.
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

    // the core case: the reviewer clears the wire, but by now the recipient's verification no longer
    // stands. The money must not land, and it must not sit in PENDING_APPROVAL forever either - it
    // goes back to the sender. The audit trail has to say why: the reviewer approved, the recipient
    // is what stopped it, and recording it as a fraud rejection would be a false record of the
    // reviewer's decision.
    @Test
    @DisplayName("Approving a held on-us wire whose recipient is no longer verified reverses it and refunds the sender - [MEANT TO FAIL]")
    void testFraudApproval_RecipientNoLongerVerified_ReversesAndRefunds() throws Exception {
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
                        // the reviewer's real verdict is preserved...
                        && tx.getDescription().contains("Fraud Review: APPROVED")
                        // ...the reversal is attributed to the recipient...
                        && tx.getDescription().contains("recipient cannot receive funds")
                        // ...and it is never filed as the reviewer having rejected it
                        && !tx.getDescription().contains("Fraud Review: REJECTED")));
    }

    // the over-blocking guard for the test above: a recipient who is still approved at review time
    // gets credited exactly as before, and the sender is not refunded
    @Test
    @DisplayName("Approving a held on-us wire whose recipient is still verified credits the destination as before - [MEANT TO PASS]")
    void testFraudApproval_RecipientStillVerified_CreditsDestination() throws Exception {
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

    // if we cannot even establish who is being paid, we cannot establish that they may be paid -
    // fail closed the same way an outright unverified recipient does, rather than crediting on a
    // guess or leaving the wire (and the sender's money) stranded in PENDING_APPROVAL
    @Test
    @DisplayName("Approving a held on-us wire whose owner lookup fails reverses it rather than crediting blind - [MEANT TO FAIL]")
    void testFraudApproval_OwnerLookupUnavailable_ReversesAndRefunds() throws Exception {
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
    // Recipient-side KYC: an APPROVED sender still cannot push money into the account of someone
    // whose own verification hasn't cleared. @RequiresKyc only ever vets the caller, so these
    // cover the other half - the user on the receiving end.
    // ==========================================

    private static final String RECIPIENT_ACCOUNT_NUMBER = "1234567890";

    // Recipient user 55 owning account 99 - the same shape account-service's by-number lookup returns.
    private void givenRecipientExists() {
        given(accountServiceClient.lookupByAccountNumber(RECIPIENT_ACCOUNT_NUMBER))
                .willReturn(new AccountServiceClient.RecipientLookupResponse(
                        99L, 55L, "CHECKING", "******7890", "ACTIVE"));
    }

    // the core case: sender is fully approved, recipient is still waiting on their verification
    // the transfer must be refused, and refused early enough that account-service is never asked to
    // move anything - there is no half-transfer to unwind once transferToRecipient has run
    @Test
    @DisplayName("Transfer to a PENDING_VERIFICATION recipient is rejected before any debit - [MEANT TO FAIL]")
    void testRecipientKyc_PendingVerificationRecipient_BlocksTransferBeforeDebit() {
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

    // same gate, harsher status - and the message must stay generic either way, the sender is not
    // told which of the two it was
    @Test
    @DisplayName("Transfer to a REJECTED recipient is rejected without naming their status - [MEANT TO FAIL]")
    void testRecipientKyc_RejectedRecipient_BlocksTransfer() {
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

    // the control case for the two above: both sides approved, so the transfer goes through
    // untouched and still delegates to account-service exactly as before
    @Test
    @DisplayName("Transfer to an APPROVED recipient completes and delegates to account-service - [MEANT TO PASS]")
    void testRecipientKyc_ApprovedRecipient_TransferSucceeds() {
        authenticateAsFullAuthUser(42);
        givenRecipientExists();
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "APPROVED"));

        var response = transferService.executeTransferToRecipient(
                42L, 1L, RECIPIENT_ACCOUNT_NUMBER, new BigDecimal("100.00"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(accountServiceClient).transferToRecipient(new AccountServiceClient.TransferRequest(
                42L, 1L, 99L, new BigDecimal("100.00")));
    }

    // an own-accounts transfer has no second person in it, so it must not pay for a second
    // profile-service round trip - the caller's own status (checked once by the aspect) is the only
    // one that exists here
    @Test
    @DisplayName("Own-account transfer still works and consults only the caller's KYC status - [MEANT TO PASS]")
    void testRecipientKyc_OwnAccountTransfer_DoesNotConsultARecipientStatus() {
        authenticateAsFullAuthUser(42);

        var response = transferService.executeTransfer(42L, 1L, 2L, new BigDecimal("100.00"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(accountServiceClient).transfer(new AccountServiceClient.TransferRequest(42L, 1L, 2L, new BigDecimal("100.00")));
        // Exactly one KYC lookup, and it was the sender's - nothing went looking for a recipient.
        verify(profileServiceClient).getKycStatus(42L);
        verifyNoMoreInteractions(profileServiceClient);
    }

    // the wire equivalent of the first test: an IBAN that resolves here means a real platform user
    // is about to be credited, so they get vetted, and nothing is reserved when they fail
    @Test
    @DisplayName("On-us wire to an unverified account holder is blocked before funds are reserved - [MEANT TO FAIL]")
    void testRecipientKyc_OnUsWireToUnverifiedHolder_BlockedBeforeDebit() {
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

    // and the boundary the recipient check must never cross: a genuinely external IBAN has no
    // platform user behind it, and another bank's customer isn't ours to verify - so this stays
    // allowed, with the sender's status the only one ever looked up
    @Test
    @DisplayName("Wire to an IBAN that doesn't resolve here is still allowed with no recipient KYC lookup - [MEANT TO PASS]")
    void testRecipientKyc_UnresolvedIbanWire_StillAllowed() {
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

    // the preview is where the frontend warns, not where it blocks - an unverified recipient still
    // answers 200 so the sender sees who they'd be paying, just flagged as unverified
    @Test
    @DisplayName("Recipient preview reports verified=false instead of failing for an unverified recipient - [MEANT TO PASS]")
    void testRecipientKyc_Preview_ReturnsVerifiedFalseWithoutFailing() throws Exception {
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

    @Test
    @DisplayName("Transfer history resolves the caller's account IDs via account-service and queries by them - [MEANT TO PASS]")
    void testTransferHistory_ResolvesOwnedAccountIdsAndReturnsRecords() throws Exception {
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
    void testTransferHistory_AccountIdFilterNotOwned_Returns403() throws Exception {
        given(accountServiceClient.getAccountIdsByUser(42L)).willReturn(List.of(1L));

        mockMvc.perform(get("/api/v1/transfers")
                .param("accountId", "999")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Transfer history is rejected for an unauthenticated caller - [MEANT TO FAIL]")
    void testTransferHistory_UnauthenticatedDenied() throws Exception {
        mockMvc.perform(get("/api/v1/transfers"))
                .andExpect(status().is4xxClientError());
    }

    // ==========================================
    // Shared-secret gate on /api/v1/internal/**. The fraud-status endpoint approves or reverses a
    // held wire, so anyone who could reach it could release or claw back real money. Until now the
    // only thing standing in the way was the k8s ingress declining to route the prefix.
    // ==========================================

    // The whole point of the filter: no secret, no entry - even though the authorize layer marks
    // this path permitAll, and even though the caller is otherwise a perfectly well-formed request.
    @Test
    @DisplayName("Block 13: Fraud-status endpoint with no X-Internal-Token is rejected with 401 - [MEANT TO FAIL]")
    void testBlock13_FraudStatus_NoInternalToken_Returns401() throws Exception {
        UUID transactionId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/internal/transfers/{id}/fraud-status", transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\",\"reviewerNotes\":\"looks fine\"}"))
                .andExpect(status().isUnauthorized())
                // Both keys, same text - the body shape every service answers this with
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());

        // Turned away in the filter, so nothing downstream ever ran - no lookup, no money.
        verify(transactionRepository, never()).findById(any());
        verify(accountServiceClient, never()).credit(any(), any());
    }

    // A wrong secret is answered exactly like a missing one, and the message gives away neither the
    // header nor the property name - a caller probing this endpoint learns nothing to go looking for.
    @Test
    @DisplayName("Block 14: Fraud-status endpoint with a wrong X-Internal-Token is rejected with 401 - [MEANT TO FAIL]")
    void testBlock14_FraudStatus_WrongInternalToken_Returns401() throws Exception {
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

    // The over-blocking guard: the correct secret still resolves a wire exactly as before. No JWT on
    // this one on purpose - a service-to-service caller has no end-user token to present, and the
    // secret is now the whole of what authorizes the call.
    @Test
    @DisplayName("Block 15: Fraud-status endpoint with the correct X-Internal-Token resolves the wire as before - [MEANT TO PASS]")
    void testBlock15_FraudStatus_CorrectInternalToken_StillWorks() throws Exception {
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

    // The other side of the gate, and the regression that would hurt most if the filter's path check
    // were wrong: a customer request carries no X-Internal-Token and must never be asked for one.
    @Test
    @DisplayName("Block 16: Customer-facing JWT endpoint still works with no X-Internal-Token header - [MEANT TO PASS]")
    void testBlock16_CustomerFacingEndpoint_NeedsNoInternalToken() throws Exception {
        given(accountServiceClient.getAccountIdsByUser(42L)).willReturn(List.of(1L, 2L));
        given(transactionRepository.findByAccountIdInWithFilters(eq(List.of(1L, 2L)), isNull(), isNull(), isNull(), any()))
                .willReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/transfers")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L))))
                .andExpect(status().isOk());
    }

    // The outbound half of the same contract. All three Feign clients here call nothing but other
    // services' /api/v1/internal/ endpoints, so the moment those services enforce their own filters,
    // a missing header on this side is every transfer failing - not a degraded feature.
    @Test
    @DisplayName("Block 17: Outbound Feign interceptor attaches the shared secret to every request - [MEANT TO PASS]")
    void testBlock17_FeignInterceptor_AttachesInternalTokenHeader() {
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

    // The trap this test exists for: the destination credit and the sender refund are two DIFFERENT
    // effects on ONE wire. Keying both off the transaction id alone would make whichever ran second
    // look like a duplicate of the first, and account-service would silently swallow it - money that
    // should have moved quietly not moving, with a success response either way.
    // Both resolutions below are driven against the SAME transaction id deliberately: keys taken
    // from two different wires would differ no matter how carelessly they were built, so only one id
    // can actually catch the mistake.
    @Test
    @DisplayName("Block 18: Destination credit and sender refund on one wire carry DIFFERENT idempotency keys - [MEANT TO FAIL]")
    void testBlock18_FraudResolution_CreditAndRefundUseDistinctIdempotencyKeys() throws Exception {
        UUID transactionId = UUID.randomUUID();
        TransactionEntity heldWire = givenHeldOnUsWire(transactionId);
        given(accountServiceClient.lookupAccountOwner(99L))
                .willReturn(new AccountServiceClient.AccountOwnerResponse(55L));
        given(profileServiceClient.getKycStatus(55L)).willReturn(Map.of("status", "APPROVED"));

        // First effect: approval credits the destination account.
        approveAsReviewer(transactionId);

        // Second effect: the same wire put back in review and rejected, refunding the sender. Not a
        // sequence a real reviewer produces - it is how one wire is made to emit both effects so
        // their keys can be compared.
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
    void testBlock19_AccountServiceUnreachableAtInitiation_Returns503AndSavesNothing() throws Exception {
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

    // The over-blocking guard for the test above, and the reason its try/catch is scoped to the
    // lookup call alone. resolveOnUsDestination also runs the recipient-KYC check, which throws for
    // an unverified recipient - catching RuntimeException around the whole method body would rewrite
    // that 403 verdict as a 503 outage and disable the recipient-KYC rule while still looking like
    // it ran. account-service answers here perfectly well; it is the recipient who is the problem.
    @Test
    @DisplayName("Block 20: Unverified recipient at wire initiation still returns 403, never 503 - [MEANT TO FAIL]")
    void testBlock20_UnverifiedRecipientAtInitiation_Returns403Not503() throws Exception {
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
