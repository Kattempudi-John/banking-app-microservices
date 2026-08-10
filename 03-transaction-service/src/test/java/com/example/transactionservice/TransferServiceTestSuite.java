package com.example.transactionservice;

import com.example.transactionservice.aspect.KycEnforcementAspect;
import com.example.transactionservice.client.AccountServiceClient;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
})
class TransferServiceTestSuite {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransferService transferService;

    @Autowired
    private ExternalWireService externalWireService;

    @MockBean
    private AccountServiceClient accountServiceClient;

    @MockBean
    private TransactionRepository transactionRepository;

    @MockBean
    private ProfileServiceClient profileServiceClient;

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

        assertThatThrownBy(() -> transferService.executeTransfer(42L, 1L, 2L, new BigDecimal("50.00")))
                .isInstanceOf(KycEnforcementAspect.KycRequiredException.class)
                .hasMessageContaining("PENDING_VERIFICATION");

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

        assertThatThrownBy(() -> externalWireService.initiateWire(42L, 1L, request))
                .isInstanceOf(KycEnforcementAspect.KycRequiredException.class)
                .hasMessageContaining("REJECTED");

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
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("PENDING_VERIFICATION")));
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
                .willReturn(new AccountServiceClient.AccountLookupResponse(99L, 55L, "CHECKING", "ACTIVE"));

        var request = new com.example.transactionservice.dto.ExternalWireRequestDto(
                VALID_IBAN, VALID_SWIFT, "Jane Doe", new BigDecimal("100.00"));

        var response = externalWireService.initiateWire(42L, 1L, request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.onUsTransfer()).isTrue();
        verify(accountServiceClient).debit(eq(1L), any());
        verify(accountServiceClient).credit(eq(99L), any());
        verify(largeTransferKafkaTemplate, never()).send(any(), any(), any());
    }

    // the flip side: an on-us wire over the threshold should still only hold funds (debit-only) at
    // initiation, exactly like a genuinely external wire does - the destination doesn't get its
    // half of the transfer until fraud review approves it (see the next test)
    @Test
    @DisplayName("On-us wire over threshold holds funds without crediting the destination yet - [MEANT TO PASS]")
    void testOnUsWire_OverThreshold_HoldsWithoutCreditingYet() {
        authenticateAsFullAuthUser(42);
        given(accountServiceClient.lookupByIban(VALID_IBAN))
                .willReturn(new AccountServiceClient.AccountLookupResponse(99L, 55L, "CHECKING", "ACTIVE"));

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

        mockMvc.perform(patch("/api/v1/internal/transfers/{id}/fraud-status", transactionId)
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
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 42L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\",\"reviewerNotes\":\"looks fine\"}"))
                .andExpect(status().isOk());

        verify(accountServiceClient, never()).credit(any(), any());
        verify(transactionRepository).save(argThat(tx -> tx.getStatus() == TransactionStatus.COMPLETED));
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
}
