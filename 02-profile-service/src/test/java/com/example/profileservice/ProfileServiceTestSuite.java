package com.example.profileservice;

import com.example.profileservice.client.AuthServiceClient;
import com.example.profileservice.dto.UpdateAlertThresholdRequestDto;
import com.example.profileservice.dto.UpdateContactInfoRequestDto;
import com.example.profileservice.dto.UpdateDailySummaryRequestDto;
import com.example.profileservice.model.KycOverrideAuditLog;
import com.example.profileservice.model.KycStatus;
import com.example.profileservice.model.UserPreferenceEntity;
import com.example.profileservice.model.UserProfile;
import com.example.profileservice.repository.KycOverrideAuditLogRepository;
import com.example.profileservice.repository.PreferenceRepository;
import com.example.profileservice.repository.UserProfileRepository;
import com.example.profileservice.service.ProfileManagementService;
import com.example.profileservice.service.ProfileManagementService.KycStatusUpdatedEvent;
import com.example.profileservice.service.ProfileManagementService.ProfileUpdatedEvent;
import com.example.profileservice.service.UserRegisteredListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
// willX(...).given(mock).method(...) rather than given(mock.method(...)).willX(...) wherever the
// setUp stub is being replaced: the given(mock.method(...)) form actually calls the mock, which
// would run setUp's answer with null arguments before the new stub is even installed.
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end tests for profile-service: KYC status reads and the vendor KYC webhook, the compliance
 * officer's manual override, the identity ("contact info") form and its write-through to
 * auth-service, alert and daily-summary preferences, the shared-secret gate on {@code
 * /api/v1/internal/}, and the Kafka listener that provisions a profile for a newly registered user.
 *
 * <h2>Why the whole context, not a slice</h2>
 * {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} rather than {@code @WebMvcTest} because
 * most of what is under test lives outside the controllers: the real security filter chain (the
 * {@code @PreAuthorize} FULL_AUTH checks and the {@code InternalTokenFilter}), the real
 * {@code ProfileManagementService} with its KYC state machine and Kafka publishing, the real
 * {@code GlobalExceptionHandler} that turns a {@code ResponseStatusException} into the JSON body the
 * frontend parses, and the real Feign {@code RequestInterceptor}. A {@code @WebMvcTest} would mock
 * the service layer away and every one of those would go untested. Three tests deliberately bypass
 * MockMvc and call {@code ProfileManagementService} / {@code UserRegisteredListener} directly, for
 * the paths that have no HTTP entry point of their own.
 *
 * <h2>Mocked versus real</h2>
 * Everything is real except four beans:
 * <ul>
 *   <li>{@code UserProfileRepository}, {@code PreferenceRepository} and
 *       {@code KycOverrideAuditLogRepository} are {@code @MockBean}s. Nothing is ever written to a
 *       database in this suite — the H2 URL in {@code @TestPropertySource} exists only so JPA and
 *       Hibernate can start the context. Because persistence is stubbed rather than transactional,
 *       there is no rollback to rely on: state does not leak between tests because each test stubs
 *       its own lookups, and the {@code @BeforeEach} fixture is rebuilt every time.</li>
 *   <li>{@code KafkaTemplate} is a {@code @MockBean} and Kafka (and Redis) auto-configuration is
 *       excluded outright, so there is no broker, embedded or otherwise. Published events are
 *       asserted as {@code verify(kafkaTemplate).send(...)} calls.</li>
 *   <li>{@code AuthServiceClient} is a {@code @MockBean} — auth-service owns the phone number now,
 *       so every identity submission goes through it, and no test needs a live service on 8081. Its
 *       default stub (see {@link #setUp()}) stands in for a healthy auth-service that accepts the
 *       number: it echoes the submitted value straight back, which is what really happens when the
 *       number is already E.164 and belongs to nobody else. Individual tests replace that stub to
 *       make it conflict, reject, normalise, or fail to connect.</li>
 * </ul>
 * Note that mocking {@code AuthServiceClient} takes Feign's own machinery out of the picture
 * entirely, which is why the outbound internal-token header is asserted separately against a bare
 * {@code RequestTemplate} through the autowired {@code internalTokenRequestInterceptor}.
 *
 * <h2>Shared fixture</h2>
 * {@code @BeforeEach setUp()} builds {@code mockUser} — one profile, id 100, in
 * {@code PENDING_VERIFICATION}, the state a brand-new profile starts in — and installs the two
 * default {@code AuthServiceClient} stubs. Tests are free to mutate {@code mockUser} before their
 * own assertions; several assert against it directly after the request, since the mocked repository
 * hands the service the same instance the test holds. There is no {@code @BeforeAll} state.
 *
 * <h2>Faking a token</h2>
 * No JWT is ever minted. Authentication is simulated with
 * {@code SecurityMockMvcRequestPostProcessors.jwt()}, which installs an already-authenticated
 * principal. The controllers read the caller's id from a {@code userId} claim, and the
 * customer-facing endpoints require the {@code SCOPE_FULL_AUTH} authority — which the resource
 * server's default converter derives from a {@code scope} claim of {@code FULL_AUTH}. So a finished
 * session can be faked either by setting that claim or by granting the authority directly; the
 * {@link #fullAuthToken(long)} and {@link #preAuthToken(long)} helpers exist so a test's intent
 * reads at the call site rather than having to be inferred from which claims were set.
 *
 * <h2>Pinned properties</h2>
 * {@code @TestPropertySource} fixes the vendor webhook HMAC secret so the signature tests can
 * compute a signature the service will accept, and deliberately sets the internal shared secret to a
 * value that is <em>not</em> the dev default — see the note on the annotation itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:profiletestdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
    "kyc.vendor.webhook.secret=SuperSecretVendorKey123!",
    // Deliberately NOT the dev default baked into InternalTokenFilter/FeignInternalTokenConfig. If
    // the tests used the default value they would still pass against a filter that ignored the
    // property entirely and compared against a hardcoded constant - which is exactly the bug that
    // would leave prod running on the dev token.
    "application.security.internal-token=test-internal-token"
})
class ProfileServiceTestSuite {

    // What the five services agree on: the header name, and that it carries this property's value.
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String INTERNAL_TOKEN = "test-internal-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProfileManagementService profileManagementService;

    @Autowired
    private UserRegisteredListener userRegisteredListener;

    // The outbound half of the internal-token change. Feign applies this to every request it sends,
    // so it is testable on its own against a bare RequestTemplate - no mock HTTP server, and no
    // relying on the @MockBean AuthServiceClient below, which never reaches Feign's machinery at all.
    @Autowired
    private RequestInterceptor internalTokenRequestInterceptor;

    @MockBean
    private UserProfileRepository userProfileRepository;

    @MockBean
    private KycOverrideAuditLogRepository auditLogRepository;

    @MockBean
    private PreferenceRepository preferenceRepository;

    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    // auth-service owns the phone number now, so every contact-info test goes through this.
    // Mocked, not called for real - these tests never need a live auth-service on 8081.
    @MockBean
    private AuthServiceClient authServiceClient;

    private UserProfile mockUser;

    @BeforeEach
    void setUp() {
        mockUser = new UserProfile();
        mockUser.setId(100L);
        mockUser.setPhoneNumber("+14155552671");
        mockUser.setAddressLine1("123 Financial Way");
        mockUser.setCity("New York");
        mockUser.setState("NY");
        mockUser.setZipCode("10001");
        mockUser.setKycStatus(KycStatus.PENDING_VERIFICATION);

        // Default stand-in for a healthy auth-service accepting the number: it echoes back what was
        // submitted, which is what really happens when the submitted value is already E.164 and
        // belongs to nobody else. Individual tests override this to make it conflict, reject, or
        // return a differently-formatted number.
        given(authServiceClient.updatePhoneNumber(any(), any())).willAnswer(invocation -> {
            AuthServiceClient.UpdatePhoneNumberRequest request = invocation.getArgument(1);
            return new AuthServiceClient.PhoneNumberResponse(request.phoneNumber());
        });
        given(authServiceClient.getPhoneNumber(any()))
                .willReturn(new AuthServiceClient.PhoneNumberResponse("+14155552671"));
    }

    /**
     * A user can hold valid credentials in auth-service and still have no profile row here, if the
     * "user-events" message that normally provisions one was never consumed. This used to answer
     * 500, which quietly blocked every transfer that user attempted, since transaction-service's
     * {@code KycEnforcementAspect} calls this same endpoint before moving any money.
     */
    @Test
    @DisplayName("Block 1: Query KYC Status Provisions a Missing Profile Instead of Failing - [MEANT TO PASS]")
    void getKycStatus_noProfileRowForUser_provisionsPendingProfileAndReturns200() throws Exception {
        given(userProfileRepository.findById(999L)).willReturn(Optional.empty());
        given(userProfileRepository.save(any(UserProfile.class))).willAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(get("/api/v1/internal/profiles/999/kyc-status")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));

        // the row has to actually be written, not just reported - otherwise the user would re-provision
        // on every single page load and still have nothing on file for transfers to check against
        ArgumentCaptor<UserProfile> savedProfile = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepository).save(savedProfile.capture());
        assertThat(savedProfile.getValue().getId()).isEqualTo(999L);
        assertThat(savedProfile.getValue().getKycStatus()).isEqualTo(KycStatus.PENDING_VERIFICATION);
    }

    @Test
    @DisplayName("Final Block: Acceptance Criteria Verification - Query Active KYC Status - [MEANT TO PASS]")
    void getKycStatus_existingProfile_returns200WithItsCurrentStatus() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        mockMvc.perform(get("/api/v1/internal/profiles/100/kyc-status")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
    }

    /**
     * The endpoint the frontend calls: it takes no userId at all, so there is no id for a caller to
     * tamper with - the status returned is always the one belonging to the token's own user.
     */
    @Test
    @DisplayName("Block 1b: /profiles/me/kyc-status Reads The Caller's Own Id From The JWT - [MEANT TO PASS]")
    void getMyKycStatus_fullAuthToken_returns200WithTheTokensOwnUsersStatus() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        mockMvc.perform(get("/api/v1/profiles/me/kyc-status")
                .with(jwt().jwt(builder -> builder.claim("userId", 100L))
                        .authorities(new SimpleGrantedAuthority("SCOPE_FULL_AUTH"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
    }

    /**
     * This is the whole reason the endpoint moved: it used to be permitAll AND sat under a path the
     * k8s ingress publishes, so anyone on the internet could read any user's KYC status by id.
     */
    @Test
    @DisplayName("Block 1c: /profiles/me/kyc-status Rejects An Unauthenticated Caller - [MEANT TO FAIL]")
    void getMyKycStatus_noAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/me/kyc-status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Block 1: Webhook Rejects Request Missing HMAC Signature Header - [MEANT TO FAIL]")
    void kycWebhook_noSignatureHeader_returns401MissingSignature() throws Exception {
        String payloadJson = objectMapper.writeValueAsString(Map.of("userId", "100", "status", "APPROVED"));

        mockMvc.perform(post("/api/v1/webhooks/kyc-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payloadJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing X-Signature header"));
    }

    @Test
    @DisplayName("Block 2: Webhook Rejects Invalid HMAC Signature - [MEANT TO FAIL]")
    void kycWebhook_signatureNotComputedWithTheSecret_returns401InvalidSignature() throws Exception {
        String payloadJson = objectMapper.writeValueAsString(Map.of("userId", "100", "status", "APPROVED"));

        mockMvc.perform(post("/api/v1/webhooks/kyc-update")
                .header("X-Signature", "InvalidSignatureValue123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payloadJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid webhook signature"));
    }

    @Test
    @DisplayName("Final Block: Acceptance Criteria Verification - Valid Webhook Updates KYC to APPROVED - [MEANT TO PASS]")
    void kycWebhook_signatureComputedWithTheSecret_returns200AndSavesApprovedStatus() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        String payloadJson = objectMapper.writeValueAsString(Map.of("userId", "100", "status", "APPROVED"));
        // the same HMAC-SHA256-over-the-raw-body the vendor computes, keyed with the secret pinned in
        // @TestPropertySource - signing the serialized string rather than re-serializing the map
        // matters, because the signature covers the exact bytes that get posted
        String validHmac = calculateHmac(payloadJson, "SuperSecretVendorKey123!");

        mockMvc.perform(post("/api/v1/webhooks/kyc-update")
                .header("X-Signature", validHmac)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payloadJson))
                .andExpect(status().isOk());

        assertThat(mockUser.getKycStatus()).isEqualTo(KycStatus.APPROVED);
        verify(userProfileRepository).save(mockUser);
    }

    /**
     * KYC vendors retry, so the same APPROVED callback arrives more than once. Re-processing a
     * status the user already holds must not put a second event on kyc-events - every consumer
     * downstream would treat it as a fresh transition.
     */
    @Test
    @DisplayName("Block 1: Process Webhook Status Unchanged Does Not Broadcast Kafka Event - [MEANT TO PASS]")
    void processKycWebhook_statusAlreadyApproved_publishesNoKafkaEvent() {
        mockUser.setKycStatus(KycStatus.APPROVED);
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        profileManagementService.processKycWebhook(100L, KycStatus.APPROVED);

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("Final Block: Acceptance Criteria Verification - KYC Status Transition Broadcasts Kafka Event - [MEANT TO PASS]")
    void processKycWebhook_statusTransitionsFromPendingToApproved_savesProfileAndPublishesKycStatusUpdatedEvent() {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        profileManagementService.processKycWebhook(100L, KycStatus.APPROVED);

        verify(userProfileRepository).save(mockUser);
        verify(kafkaTemplate).send(eq("kyc-events"), eq("100"), any(KycStatusUpdatedEvent.class));
    }

    @Test
    @DisplayName("Block 1: Admin Override Fails When Reason Text is Blank - [MEANT TO FAIL]")
    void adminOverrideKyc_reasonIsOnlyWhitespace_returns400ReasonMandatory() throws Exception {
        String requestJson = objectMapper.writeValueAsString(Map.of("status", "APPROVED", "reason", "  "));

        mockMvc.perform(patch("/api/v1/admin/profiles/100/kyc")
                .with(jwt().jwt(j -> j.claim("userId", 500L)).authorities(new SimpleGrantedAuthority("ROLE_COMPLIANCE_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Override reason is mandatory"));
    }

    /**
     * The override is the escape hatch for a status the vendor got wrong, so it has to leave a
     * trail: the audit row is what makes an officer's manual approval reconstructable afterwards,
     * and the kyc-events publish is what stops the rest of the platform from still believing the
     * vendor's verdict.
     */
    @Test
    @DisplayName("Final Block: Acceptance Criteria Verification - Admin Override Updates DB, Audits, and Broadcasts Kafka Event - [MEANT TO PASS]")
    void adminOverrideKyc_reasonSupplied_returns200SavesAuditLogAndPublishesKycStatusUpdatedEvent() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        String requestJson = objectMapper.writeValueAsString(Map.of("status", "APPROVED", "reason", "Manual verification of physical passport."));

        mockMvc.perform(patch("/api/v1/admin/profiles/100/kyc")
                .with(jwt().jwt(j -> j.claim("userId", 500L)).authorities(new SimpleGrantedAuthority("ROLE_COMPLIANCE_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("KYC status manually overridden by compliance officer"));

        verify(auditLogRepository).save(any(KycOverrideAuditLog.class));
        verify(userProfileRepository).save(mockUser);
        verify(kafkaTemplate).send(eq("kyc-events"), eq("100"), any(KycStatusUpdatedEvent.class));
    }

    /**
     * The one test in the file authenticated with {@code @WithMockUser} rather than a FULL_AUTH
     * token, and it still reaches a 400 rather than the 403 that principal would earn: bean
     * validation of the request body runs during argument resolution, before the method-security
     * interceptor around the controller method ever gets a say.
     */
    @Test
    @DisplayName("Block 1: Contact Info Update Rejects Invalid International Phone Format - [MEANT TO FAIL]")
    @WithMockUser(username = "100")
    void updateContactInfo_phoneNumberNotInInternationalFormat_returns400() throws Exception {
        UpdateContactInfoRequestDto dto = new UpdateContactInfoRequestDto();
        dto.setLegalName("Jane Q Public");
        dto.setDateOfBirth(LocalDate.of(1990, 4, 17));
        dto.setPhoneNumber("INVALID_PHONE_123");
        dto.setAddressLine1("123 Main St");
        dto.setCity("Boston");
        dto.setState("MA");
        dto.setZipCode("02108");

        mockMvc.perform(put("/api/v1/profiles/me/contact-info")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Final Block: Acceptance Criteria Verification - Valid Contact Info Update Persists to Database - [MEANT TO PASS]")
    void updateContactInfo_completeIdentitySubmission_returns200ApprovedAndPersistsTheNewFields() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        UpdateContactInfoRequestDto dto = new UpdateContactInfoRequestDto();
        dto.setLegalName("Jane Q Public");
        dto.setDateOfBirth(LocalDate.of(1990, 4, 17));
        dto.setPhoneNumber("+12025550143");
        dto.setAddressLine1("456 Innovation Blvd");
        dto.setCity("San Jose");
        dto.setState("CA");
        dto.setZipCode("95110");

        mockMvc.perform(put("/api/v1/profiles/me/contact-info")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Profile updated successfully"))
                // Submitting a complete identity IS the verification, so the response reports the
                // status it produced rather than making the page ask for it separately.
                .andExpect(jsonPath("$.kycStatus").value("APPROVED"));

        assertThat(mockUser.getLegalName()).isEqualTo("Jane Q Public");
        assertThat(mockUser.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 4, 17));
        assertThat(mockUser.getPhoneNumber()).isEqualTo("+12025550143");
        assertThat(mockUser.getAddressLine1()).isEqualTo("456 Innovation Blvd");
        assertThat(mockUser.getKycStatus()).isEqualTo(KycStatus.APPROVED);

        // Twice, not once: the contact-info write and the KYC promotion are separate saves inside the
        // one transaction.
        verify(userProfileRepository, times(2)).save(mockUser);
        verify(kafkaTemplate).send(eq("kyc-events"), eq("100"), any(KycStatusUpdatedEvent.class));
    }

    /**
     * The counterpart to the auto-approval above: a REJECTED applicant editing their details must
     * not be able to clear their own rejection by resubmitting the form. That call belongs to the
     * vendor or to a compliance officer's override.
     */
    @Test
    @DisplayName("Block: Contact Info Update Does Not Re-Approve A REJECTED Applicant - [MEANT TO PASS]")
    void updateContactInfo_applicantAlreadyRejected_returns200StillRejectedAndPublishesNoKycEvent() throws Exception {
        mockUser.setKycStatus(KycStatus.REJECTED);
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        UpdateContactInfoRequestDto dto = new UpdateContactInfoRequestDto();
        dto.setLegalName("Jane Q Public");
        dto.setDateOfBirth(LocalDate.of(1990, 4, 17));
        dto.setPhoneNumber("+12025550143");
        dto.setAddressLine1("456 Innovation Blvd");
        dto.setCity("San Jose");
        dto.setState("CA");
        dto.setZipCode("95110");

        mockMvc.perform(put("/api/v1/profiles/me/contact-info")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("REJECTED"));

        assertThat(mockUser.getKycStatus()).isEqualTo(KycStatus.REJECTED);
        verify(kafkaTemplate, never()).send(eq("kyc-events"), anyString(), any(KycStatusUpdatedEvent.class));
    }

    /**
     * An applicant under the minimum age is rejected outright rather than saved and left silently
     * unverified, which would leave them resubmitting the same form wondering why transfers are
     * blocked.
     */
    @Test
    @DisplayName("Block: Contact Info Update Rejects An Applicant Under 18 - [MEANT TO FAIL]")
    void updateContactInfo_dateOfBirthUnder18_returns400AndLeavesKycPending() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        UpdateContactInfoRequestDto dto = new UpdateContactInfoRequestDto();
        dto.setLegalName("Too Young");
        // relative to today rather than a fixed date, so the fixture cannot quietly age past 18 and
        // start testing the opposite branch some years from now
        dto.setDateOfBirth(LocalDate.now().minusYears(10));
        dto.setPhoneNumber("+12025550143");
        dto.setAddressLine1("456 Innovation Blvd");
        dto.setCity("San Jose");
        dto.setState("CA");
        dto.setZipCode("95110");

        mockMvc.perform(put("/api/v1/profiles/me/contact-info")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());

        assertThat(mockUser.getKycStatus()).isEqualTo(KycStatus.PENDING_VERIFICATION);
    }

    /**
     * A legal name and date of birth are what make this a verification rather than an address
     * change, so the endpoint must not accept a submission missing either.
     */
    @Test
    @DisplayName("Block: Contact Info Update Rejects A Missing Legal Name And Date Of Birth - [MEANT TO FAIL]")
    void updateContactInfo_legalNameAndDateOfBirthAbsent_returns400() throws Exception {
        UpdateContactInfoRequestDto dto = new UpdateContactInfoRequestDto();
        dto.setPhoneNumber("+12025550143");
        dto.setAddressLine1("456 Innovation Blvd");
        dto.setCity("San Jose");
        dto.setState("CA");
        dto.setZipCode("95110");

        mockMvc.perform(put("/api/v1/profiles/me/contact-info")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    // ==========================================
    // Half-authenticated sessions (a correct password, no 2FA code yet)
    // ==========================================

    /**
     * The account-takeover this endpoint's {@code @PreAuthorize} exists to stop, reproduced end to
     * end: log in with a stolen password from an unrecognised device, take the PRE_AUTH token
     * auth-service answers with instead of finishing 2FA, and put a new phone number on the identity
     * form. It used to answer 200 APPROVED and write the attacker's number through to auth-service's
     * users table - which is where 2FA codes are delivered from, so the second factor moved to the
     * attacker and the password alone became the whole account.
     */
    @Test
    @DisplayName("Block: PUT contact-info Is Forbidden To A PRE_AUTH Token And Never Moves The 2FA Phone Number - [MEANT TO FAIL]")
    void updateContactInfo_preAuthTokenBeforeTwoFactor_returns403AndNeverTouchesAuthServiceOrTheProfile() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        UpdateContactInfoRequestDto dto = identityDtoWithPhone("+15550009999");

        mockMvc.perform(put("/api/v1/profiles/me/contact-info")
                .with(preAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());

        // The call auth-service is on the other end of is the one that matters most here: it must not
        // have happened at all, because that is the write that redirects the victim's login codes. A
        // 403 with the phone number already moved would be no fix.
        verify(authServiceClient, never()).updatePhoneNumber(any(), any());
        verify(authServiceClient, never()).getPhoneNumber(any());

        // and nothing local either - not saved, not broadcast, and above all not approved
        verify(userProfileRepository, never()).save(any(UserProfile.class));
        verify(kafkaTemplate, never()).send(any(), any(), any());
        assertThat(mockUser.getKycStatus()).isEqualTo(KycStatus.PENDING_VERIFICATION);
        assertThat(mockUser.getPhoneNumber()).isEqualTo("+14155552671");
        assertThat(mockUser.getLegalName()).isNull();
    }

    /**
     * The other half of that rule: the fix is a check on the session, not on the endpoint. A user who
     * did finish 2FA still submits this form exactly as before.
     */
    @Test
    @DisplayName("Block: PUT contact-info Still Succeeds Once 2FA Is Complete (FULL_AUTH Token) - [MEANT TO PASS]")
    void updateContactInfo_fullAuthTokenAfterTwoFactor_returns200ApprovedAndWritesThePhoneNumberThrough() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        UpdateContactInfoRequestDto dto = identityDtoWithPhone("+12025550143");

        mockMvc.perform(put("/api/v1/profiles/me/contact-info")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("APPROVED"));

        verify(authServiceClient).updatePhoneNumber(eq(100L), any());
        assertThat(mockUser.getPhoneNumber()).isEqualTo("+12025550143");
    }

    // The rest of the customer-facing surface, pinned so the gap that opened on contact-info cannot
    // quietly reopen on a neighbour. Each of these already required SCOPE_FULL_AUTH; these tests are
    // what makes that a rule the suite enforces rather than a detail someone has to notice.
    @Test
    @DisplayName("Block: GET contact-info Is Forbidden To A PRE_AUTH Token - [MEANT TO FAIL]")
    void getMyContactInfo_preAuthToken_returns403AndNeverReadsThePhoneNumber() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/me/contact-info")
                .with(preAuthToken(100L)))
                .andExpect(status().isForbidden());

        verify(authServiceClient, never()).getPhoneNumber(any());
    }

    @Test
    @DisplayName("Block: GET /profiles/me/kyc-status Is Forbidden To A PRE_AUTH Token - [MEANT TO FAIL]")
    void getMyKycStatus_preAuthToken_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/me/kyc-status")
                .with(preAuthToken(100L)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Block: Alert preference writes are forbidden to a PRE_AUTH token - [MEANT TO FAIL]")
    void updateAlertThreshold_preAuthToken_returns403AndSavesNothing() throws Exception {
        UpdateAlertThresholdRequestDto dto = new UpdateAlertThresholdRequestDto(new BigDecimal("250.00"));

        mockMvc.perform(put("/api/v1/profile/alerts/threshold")
                .with(preAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());

        verify(preferenceRepository, never()).save(any());
    }

    /**
     * This response carries the user's email address, so a session that has not finished proving who
     * it is does not get to read it.
     */
    @Test
    @DisplayName("Block: GET alerts/me Is Forbidden To A PRE_AUTH Token - [MEANT TO FAIL]")
    void getMyPreferences_preAuthToken_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/profile/alerts/me")
                .with(preAuthToken(100L)))
                .andExpect(status().isForbidden());
    }

    // ==========================================
    // Phone number ownership (auth-service is the owner, this service mirrors)
    // ==========================================

    /**
     * The bug this whole change exists for: signup already answers 409 for a number someone else
     * holds, but this form used to write the number straight into the local table with no check at
     * all, so it was a way around that rule - and it handed out a KYC approval on the way past.
     */
    @Test
    @DisplayName("Block: Contact Info Update Is Rejected When The Phone Number Belongs To Another User - [MEANT TO FAIL]")
    void updateContactInfo_authServiceReportsThePhoneNumberHeldByAnotherUser_returns409AndPersistsNothing() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));
        willThrow(new ResponseStatusException(HttpStatus.CONFLICT, "That phone number is already registered"))
                .given(authServiceClient).updatePhoneNumber(eq(100L), any());

        UpdateContactInfoRequestDto dto = identityDtoWithPhone("+15550001111");

        mockMvc.perform(put("/api/v1/profiles/me/contact-info")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict());

        // nothing saved, nothing broadcast, and above all not approved - an identity claimed on
        // someone else's phone number is the exact submission that must not clear KYC
        verify(userProfileRepository, never()).save(any(UserProfile.class));
        verify(kafkaTemplate, never()).send(any(), any(), any());
        assertThat(mockUser.getKycStatus()).isEqualTo(KycStatus.PENDING_VERIFICATION);
        assertThat(mockUser.getPhoneNumber()).isEqualTo("+14155552671");
        assertThat(mockUser.getLegalName()).isNull();
    }

    /**
     * The other half of that rule: your own unchanged number is not a conflict, otherwise nobody
     * could ever correct their address without also being forced to change their phone number.
     */
    @Test
    @DisplayName("Block: Re-Submitting Your Own Unchanged Phone Number Succeeds - [MEANT TO PASS]")
    void updateContactInfo_resubmittingTheCallersOwnUnchangedPhoneNumber_returns200Approved() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        UpdateContactInfoRequestDto dto = identityDtoWithPhone("+14155552671");

        mockMvc.perform(put("/api/v1/profiles/me/contact-info")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("APPROVED"));

        assertThat(mockUser.getPhoneNumber()).isEqualTo("+14155552671");
    }

    /**
     * The stored value is whatever auth-service answered with, not the result of normalizing the
     * input a second time here - one owner of the format, so the two copies cannot drift.
     */
    @Test
    @DisplayName("Block: Stored Phone Number Is The E.164 Value auth-service Returned - [MEANT TO PASS]")
    void updateContactInfo_authServiceEchoesBackANormalizedNumber_storesThatE164ValueNotTheInput() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));
        willReturn(new AuthServiceClient.PhoneNumberResponse("+15712856947"))
                .given(authServiceClient).updatePhoneNumber(eq(100L), any());

        UpdateContactInfoRequestDto dto = identityDtoWithPhone("571-285-6947");

        mockMvc.perform(put("/api/v1/profiles/me/contact-info")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        // the raw string the user typed is what goes out to auth-service, since it is the one doing
        // the normalizing and the uniqueness check
        verify(authServiceClient).updatePhoneNumber(eq(100L),
                argThat(request -> "571-285-6947".equals(request.phoneNumber())));
        assertThat(mockUser.getPhoneNumber()).isEqualTo("+15712856947");
    }

    /**
     * auth-service's own verdict on an unusable number is passed straight through, so the user is
     * told the same thing here as they would be told at signup.
     */
    @Test
    @DisplayName("Block: Contact Info Update Surfaces auth-service's 400 For An Unresolvable Number - [MEANT TO FAIL]")
    void updateContactInfo_authServiceRejectsTheNumberWith400_returns400AndSavesNothing() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));
        willThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Please enter a valid phone number, e.g. 571-285-6947 or +15712856947"))
                .given(authServiceClient).updatePhoneNumber(eq(100L), any());

        // passes the DTO's loose "looks like a phone number" pattern, so it reaches auth-service and
        // is refused there - which is the branch under test
        UpdateContactInfoRequestDto dto = identityDtoWithPhone("+1 234 5");

        mockMvc.perform(put("/api/v1/profiles/me/contact-info")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());

        verify(userProfileRepository, never()).save(any(UserProfile.class));
        assertThat(mockUser.getKycStatus()).isEqualTo(KycStatus.PENDING_VERIFICATION);
    }

    /**
     * A connection failure reaches Feign as a {@code RetryableException}, never touching the error
     * decoder - hence the bare {@code RuntimeException} standing in for it. The point of this test is
     * that "auth-service is down" fails the submission rather than falling back to saving locally,
     * which would approve a user on a number nobody ever verified.
     */
    @Test
    @DisplayName("Block: auth-service Being Unreachable Does Not Save Or Approve Anything - [MEANT TO FAIL]")
    void updateContactInfo_authServiceUnreachable_returns503AndDoesNotSaveOrApprove() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));
        willThrow(new RuntimeException("Connection refused: localhost/127.0.0.1:8081"))
                .given(authServiceClient).updatePhoneNumber(eq(100L), any());

        UpdateContactInfoRequestDto dto = identityDtoWithPhone("+12025550143");

        mockMvc.perform(put("/api/v1/profiles/me/contact-info")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isServiceUnavailable());

        verify(userProfileRepository, never()).save(any(UserProfile.class));
        verify(kafkaTemplate, never()).send(any(), any(), any());
        assertThat(mockUser.getKycStatus()).isEqualTo(KycStatus.PENDING_VERIFICATION);
        assertThat(mockUser.getPhoneNumber()).isEqualTo("+14155552671");
    }

    // ==========================================
    // GET /profiles/me/contact-info (pre-fills the identity form)
    // ==========================================

    /**
     * Takes no userId at all, same as the kyc-status endpoint next to it - the fields returned are
     * always the token's own user's, and the phone number is auth-service's copy, not the mirror.
     */
    @Test
    @DisplayName("Block: GET contact-info Returns The Caller's Own Fields - [MEANT TO PASS]")
    void getMyContactInfo_fullAuthToken_returns200WithTheCallersFieldsAndAuthServicesPhoneNumber() throws Exception {
        mockUser.setLegalName("Jane Q Public");
        mockUser.setDateOfBirth(LocalDate.of(1990, 4, 17));
        mockUser.setAddressLine2("Apt 4B");
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));
        willReturn(new AuthServiceClient.PhoneNumberResponse("+15712856947"))
                .given(authServiceClient).getPhoneNumber(100L);

        mockMvc.perform(get("/api/v1/profiles/me/contact-info")
                .with(jwt().jwt(builder -> builder.claim("userId", 100L))
                        .authorities(new SimpleGrantedAuthority("SCOPE_FULL_AUTH"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value("Jane Q Public"))
                // ISO yyyy-MM-dd, which is what <input type="date"> expects back
                .andExpect(jsonPath("$.dateOfBirth").value("1990-04-17"))
                // auth-service's value, deliberately different from the local mirror, so a response
                // built from the local column would fail here
                .andExpect(jsonPath("$.phoneNumber").value("+15712856947"))
                .andExpect(jsonPath("$.addressLine1").value("123 Financial Way"))
                .andExpect(jsonPath("$.addressLine2").value("Apt 4B"))
                .andExpect(jsonPath("$.city").value("New York"))
                .andExpect(jsonPath("$.state").value("NY"))
                .andExpect(jsonPath("$.zipCode").value("10001"));
    }

    /**
     * An auth-service outage must not blank the form out - a blank form is what gets retyped wrong,
     * which is the behaviour that created the duplicate numbers in the first place.
     */
    @Test
    @DisplayName("Block: GET contact-info Falls Back To The Local Phone Copy When auth-service Is Down - [MEANT TO PASS]")
    void getMyContactInfo_authServiceUnreachable_returns200WithTheLocalPhoneNumberCopy() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));
        willThrow(new RuntimeException("Connection refused: localhost/127.0.0.1:8081"))
                .given(authServiceClient).getPhoneNumber(100L);

        mockMvc.perform(get("/api/v1/profiles/me/contact-info")
                .with(jwt().jwt(builder -> builder.claim("userId", 100L))
                        .authorities(new SimpleGrantedAuthority("SCOPE_FULL_AUTH"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+14155552671"));
    }

    /**
     * Same reasoning as the kyc-status endpoint: this response carries a legal name and date of
     * birth, so an unauthenticated caller gets nothing.
     */
    @Test
    @DisplayName("Block: GET contact-info Rejects An Unauthenticated Caller - [MEANT TO FAIL]")
    void getMyContactInfo_noAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/me/contact-info"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Calls the service layer directly rather than going through MockMvc, because the event, not the
     * response, is the subject: profile-events is what audit-service and notification-service both
     * end up listening for downstream.
     */
    @Test
    @DisplayName("Final Block: Acceptance Criteria Verification - Profile Update Publishes ProfileUpdatedEvent to Kafka - [MEANT TO PASS]")
    void updateContactInfo_calledOnTheServiceDirectly_publishesProfileUpdatedEventToProfileEvents() {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        UpdateContactInfoRequestDto dto = new UpdateContactInfoRequestDto();
        dto.setPhoneNumber("+12025550143");
        dto.setAddressLine1("789 Security Way");
        dto.setCity("Austin");
        dto.setState("TX");
        dto.setZipCode("73301");

        profileManagementService.updateContactInfo(100L, dto);

        verify(kafkaTemplate).send(eq("profile-events"), eq("100"), any(ProfileUpdatedEvent.class));
    }

    @Test
    @DisplayName("Block: Update alert threshold with a valid payload persists the preference - [MEANT TO PASS]")
    void updateAlertThreshold_noExistingPreferenceRow_returns200AndSavesTheUserIdAndAmount() throws Exception {
        given(preferenceRepository.findByUserId(100L)).willReturn(Optional.empty());

        UpdateAlertThresholdRequestDto dto = new UpdateAlertThresholdRequestDto(new BigDecimal("250.00"));

        mockMvc.perform(put("/api/v1/profile/alerts/threshold")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 100L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        verify(preferenceRepository).save(argThat(entity -> entity.getUserId().equals(100L)));
        verify(preferenceRepository).save(argThat(entity -> entity.getAlertThresholdAmount().compareTo(new BigDecimal("250.00")) == 0));
    }

    @Test
    @DisplayName("Block (fixed): Threshold-only payload is sufficient - daily-summary fields are no longer required - [MEANT TO PASS]")
    void updateAlertThreshold_payloadCarriesOnlyTheAmount_returns200AndFillsTheUntouchedFieldsWithDefaults() throws Exception {
        // /threshold and /daily-summary now use separate DTOs (UpdateAlertThresholdRequestDto /
        // UpdateDailySummaryRequestDto), so a client updating only the threshold no longer has to
        // resend an unrelated dailySummaryEnabled/timezone. Replaces the old test that documented
        // the field coupling as a gap.
        given(preferenceRepository.findByUserId(100L)).willReturn(Optional.empty());
        String thresholdOnlyPayload = "{\"alertThresholdAmount\": 250.00}";

        mockMvc.perform(put("/api/v1/profile/alerts/threshold")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 100L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(thresholdOnlyPayload))
                .andExpect(status().isOk());

        // New users get the same defaults as the V3 migration's column defaults for the
        // untouched fields (100.00 / false / UTC), not null.
        verify(preferenceRepository).save(argThat(entity -> entity.getAlertThresholdAmount().compareTo(new BigDecimal("250.00")) == 0));
        verify(preferenceRepository).save(argThat(entity -> Boolean.FALSE.equals(entity.getDailySummaryEnabled())));
        verify(preferenceRepository).save(argThat(entity -> "UTC".equals(entity.getTimezone())));
    }

    @Test
    @DisplayName("Block: Updating the threshold leaves an existing daily-summary preference untouched - [MEANT TO PASS]")
    void updateAlertThreshold_existingRowHasDailySummarySettings_savesTheNewAmountAndLeavesThemAlone() throws Exception {
        UserPreferenceEntity existing = new UserPreferenceEntity();
        existing.setUserId(100L);
        existing.setAlertThresholdAmount(new BigDecimal("100.00"));
        existing.setDailySummaryEnabled(true);
        existing.setTimezone("Europe/London");
        given(preferenceRepository.findByUserId(100L)).willReturn(Optional.of(existing));

        UpdateAlertThresholdRequestDto dto = new UpdateAlertThresholdRequestDto(new BigDecimal("300.00"));

        mockMvc.perform(put("/api/v1/profile/alerts/threshold")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 100L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        verify(preferenceRepository).save(argThat(entity -> entity.getAlertThresholdAmount().compareTo(new BigDecimal("300.00")) == 0));
        verify(preferenceRepository).save(argThat(entity -> Boolean.TRUE.equals(entity.getDailySummaryEnabled())));
        verify(preferenceRepository).save(argThat(entity -> "Europe/London".equals(entity.getTimezone())));
    }

    @Test
    @DisplayName("Block: Invalid IANA timezone identifier is rejected - [MEANT TO FAIL]")
    void updateDailySummary_timezoneIsNotAnIanaIdentifier_returns400() throws Exception {
        UpdateDailySummaryRequestDto dto = new UpdateDailySummaryRequestDto(true, "Not/A_Real_Zone");

        mockMvc.perform(put("/api/v1/profile/alerts/daily-summary")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 100L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Final Block: Acceptance Criteria Verification - Daily summary opt-in persists enabled flag and timezone - [MEANT TO PASS]")
    void updateDailySummary_optInWithARealTimezone_returns200AndSavesTheFlagAndTimezone() throws Exception {
        given(preferenceRepository.findByUserId(100L)).willReturn(Optional.empty());

        UpdateDailySummaryRequestDto dto = new UpdateDailySummaryRequestDto(true, "Europe/London");

        mockMvc.perform(put("/api/v1/profile/alerts/daily-summary")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 100L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").doesNotExist()); // endpoint returns a plain string body, not JSON

        verify(preferenceRepository).save(argThat(entity -> entity.getUserId().equals(100L)));
        verify(preferenceRepository).save(argThat(entity -> Boolean.TRUE.equals(entity.getDailySummaryEnabled())));
        verify(preferenceRepository).save(argThat(entity -> "Europe/London".equals(entity.getTimezone())));
    }

    @Test
    @DisplayName("Block: Updating daily-summary settings leaves an existing alert threshold untouched - [MEANT TO PASS]")
    void updateDailySummary_existingRowHasAnAlertThreshold_savesTheNewSummarySettingsAndLeavesTheThreshold() throws Exception {
        UserPreferenceEntity existing = new UserPreferenceEntity();
        existing.setUserId(100L);
        existing.setAlertThresholdAmount(new BigDecimal("500.00"));
        existing.setDailySummaryEnabled(false);
        existing.setTimezone("UTC");
        given(preferenceRepository.findByUserId(100L)).willReturn(Optional.of(existing));

        UpdateDailySummaryRequestDto dto = new UpdateDailySummaryRequestDto(true, "Asia/Tokyo");

        mockMvc.perform(put("/api/v1/profile/alerts/daily-summary")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 100L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        verify(preferenceRepository).save(argThat(entity -> entity.getAlertThresholdAmount().compareTo(new BigDecimal("500.00")) == 0));
        verify(preferenceRepository).save(argThat(entity -> Boolean.TRUE.equals(entity.getDailySummaryEnabled())));
        verify(preferenceRepository).save(argThat(entity -> "Asia/Tokyo".equals(entity.getTimezone())));
    }

    @Test
    @DisplayName("Block: Alert preference writes are denied to a caller with no token at all - [MEANT TO FAIL]")
    void updateAlertThreshold_noAuthenticationAtAll_returns4xxClientError() throws Exception {
        UpdateAlertThresholdRequestDto dto = new UpdateAlertThresholdRequestDto(new BigDecimal("100.00"));

        mockMvc.perform(put("/api/v1/profile/alerts/threshold")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().is4xxClientError());
    }

    /**
     * The endpoint the Alert Preferences page calls - authenticated, and scoped to the token's own
     * user, so nobody can read another person's threshold or email by changing an id.
     */
    @Test
    @DisplayName("Block: GET alerts/me returns the caller's own preferences - [MEANT TO PASS]")
    void getMyPreferences_fullAuthToken_returns200WithTheTokensOwnUsersPreferences() throws Exception {
        UserPreferenceEntity existing = new UserPreferenceEntity();
        existing.setUserId(100L);
        existing.setAlertThresholdAmount(new BigDecimal("250.00"));
        existing.setDailySummaryEnabled(true);
        existing.setTimezone("Europe/London");
        given(preferenceRepository.findByUserId(100L)).willReturn(Optional.of(existing));

        mockMvc.perform(get("/api/v1/profile/alerts/me")
                .with(jwt().jwt(builder -> builder.claim("userId", 100L))
                        .authorities(new SimpleGrantedAuthority("SCOPE_FULL_AUTH"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(100))
                .andExpect(jsonPath("$.alertThresholdAmount").value(250.00));
    }

    /**
     * The whole reason the service-to-service reads moved to /api/v1/internal/: now that the ingress
     * routes /api/v1/profile, anything left unauthenticated under it would be public - and this
     * response carries the user's email address.
     */
    @Test
    @DisplayName("Block: GET alerts/me Rejects An Unauthenticated Caller - [MEANT TO FAIL]")
    void getMyPreferences_noAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/profile/alerts/me"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * notification-service asking for a user's preferences: no end-user JWT, since this is a
     * service-to-service call, just the shared internal token that replaced "the ingress doesn't
     * route this prefix" as the only protection.
     */
    @Test
    @DisplayName("Block: GET internal preferences returns the stored values without an end-user JWT - [MEANT TO PASS]")
    void getInternalPreferences_existingRow_returns200WithTheStoredValues() throws Exception {
        UserPreferenceEntity existing = new UserPreferenceEntity();
        existing.setUserId(100L);
        existing.setAlertThresholdAmount(new BigDecimal("250.00"));
        existing.setDailySummaryEnabled(true);
        existing.setTimezone("Europe/London");
        given(preferenceRepository.findByUserId(100L)).willReturn(Optional.of(existing));

        mockMvc.perform(get("/api/v1/internal/profiles/100/preferences")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(100))
                .andExpect(jsonPath("$.alertThresholdAmount").value(250.00))
                .andExpect(jsonPath("$.dailySummaryEnabled").value(true))
                .andExpect(jsonPath("$.timezone").value("Europe/London"));
    }

    /**
     * A user with no preference row yet should still get a coherent response (the documented
     * defaults), not a 404 - and this lookup must never persist anything on its own.
     */
    @Test
    @DisplayName("Block: GET internal preferences returns documented defaults for a user with no row yet - [MEANT TO PASS]")
    void getInternalPreferences_noRowForThatUser_returns200WithDocumentedDefaultsAndSavesNothing() throws Exception {
        given(preferenceRepository.findByUserId(999L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/internal/profiles/999/preferences")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertThresholdAmount").value(100.00))
                .andExpect(jsonPath("$.dailySummaryEnabled").value(false))
                .andExpect(jsonPath("$.timezone").value("UTC"));

        verify(preferenceRepository, never()).save(any());
    }

    /**
     * The daily-summary batch job asking for every user opted in for one specific timezone - the
     * response shape, and that it is reachable on the internal token alone (there is no user in the
     * room on this sweep at all, so there is no JWT it could ever send).
     */
    @Test
    @DisplayName("Block: GET daily-summary-users filters by timezone and opt-in flag - [MEANT TO PASS]")
    void getDailySummaryUsers_timezoneSupplied_returns200WithTheOptedInUsersInThatZone() throws Exception {
        UserPreferenceEntity optedIn = new UserPreferenceEntity();
        optedIn.setUserId(200L);
        optedIn.setAlertThresholdAmount(new BigDecimal("100.00"));
        optedIn.setDailySummaryEnabled(true);
        optedIn.setTimezone("America/New_York");
        given(preferenceRepository.findByDailySummaryEnabledTrueAndTimezone("America/New_York"))
                .willReturn(List.of(optedIn));

        mockMvc.perform(get("/api/v1/internal/profiles/daily-summary-users")
                .param("timezone", "America/New_York")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(200));
    }

    // ==========================================
    // Per-user daily summary hour (dailySummaryHour)
    // ==========================================
    //
    // The send hour used to be a single notification.daily-summary.hour in notification-service, so
    // 8am meant 8am for every customer on the platform or for nobody. The timezone and the on/off
    // toggle were already per-user on this table; the hour was the one part of that same schedule
    // still living outside the user's own preferences.

    @Test
    @DisplayName("Block: A chosen daily-summary hour persists and reads back on both the customer and internal responses - [MEANT TO PASS]")
    void updateDailySummary_hourChosen_savesItAndReadsItBackOnBothTheCustomerAndInternalResponses() throws Exception {
        UserPreferenceEntity existing = optedInPreference(100L, "Europe/London", 8);
        given(preferenceRepository.findByUserId(100L)).willReturn(Optional.of(existing));

        mockMvc.perform(put("/api/v1/profile/alerts/daily-summary")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateDailySummaryRequestDto(true, "Europe/London", 17))))
                .andExpect(status().isOk());

        verify(preferenceRepository).save(argThat(entity -> Integer.valueOf(17).equals(entity.getDailySummaryHour())));

        // read back through BOTH shapes off the same row: the Alert Preferences page pre-fills its
        // hour picker from the first and notification-service schedules the send from the second, so
        // a field that appears on only one of them is a preference that works in one direction only
        mockMvc.perform(get("/api/v1/profile/alerts/me")
                .with(fullAuthToken(100L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailySummaryHour").value(17));

        mockMvc.perform(get("/api/v1/internal/profiles/100/preferences")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailySummaryHour").value(17));
    }

    /**
     * Both ends of the range, because an off-by-one in the bounds silently deletes an hour a user can
     * actually pick - either midnight or 11pm, depending on which end got it wrong.
     */
    @Test
    @DisplayName("Block: Hour 0 and hour 23 are both accepted - [MEANT TO PASS]")
    void updateDailySummary_hourAtEitherEndOfTheRange_returns200AndSavesBoth0And23() throws Exception {
        // no existing row, so each request builds its own entity - reusing one would mean both
        // captured saves point at the same object, and the first assertion would really be reading
        // the value the second request left behind
        given(preferenceRepository.findByUserId(100L)).willReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/profile/alerts/daily-summary")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateDailySummaryRequestDto(true, "UTC", 0))))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/profile/alerts/daily-summary")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateDailySummaryRequestDto(true, "UTC", 23))))
                .andExpect(status().isOk());

        verify(preferenceRepository).save(argThat(entity -> Integer.valueOf(0).equals(entity.getDailySummaryHour())));
        verify(preferenceRepository).save(argThat(entity -> Integer.valueOf(23).equals(entity.getDailySummaryHour())));
    }

    /**
     * 24 is the plausible typo for midnight and -1 for "an hour earlier". Neither may be quietly
     * clamped to 23 or 0 and stored: the user would be told their choice was saved and then be
     * emailed at a different time than the one showing on their own preferences page.
     */
    @Test
    @DisplayName("Block: An hour outside 0-23 is rejected with a readable 400 and nothing is saved - [MEANT TO FAIL]")
    void updateDailySummary_hourOutside0To23_returns400WithTheConstraintMessageAndSavesNothing() throws Exception {
        given(preferenceRepository.findByUserId(100L)).willReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/profile/alerts/daily-summary")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateDailySummaryRequestDto(true, "UTC", 24))))
                .andExpect(status().isBadRequest())
                // the constraint's own wording, under both keys - the frontend's extractApiError
                // reads "error" first and "message" second, and before GlobalExceptionHandler
                // learned about validation failures this arrived as the bare words "Bad Request"
                .andExpect(jsonPath("$.error").value("Daily summary hour must be between 0 and 23"))
                .andExpect(jsonPath("$.message").value("Daily summary hour must be between 0 and 23"));

        mockMvc.perform(put("/api/v1/profile/alerts/daily-summary")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateDailySummaryRequestDto(true, "UTC", -1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Daily summary hour must be between 0 and 23"));

        verify(preferenceRepository, never()).save(any());
    }

    /**
     * A payload that never mentions the hour is not a request to reset it - a client that only knows
     * how to flip the toggle must not silently drag every user it touches back to 8am.
     */
    @Test
    @DisplayName("Block: A daily-summary payload with no hour leaves the user's existing hour alone - [MEANT TO PASS]")
    void updateDailySummary_payloadOmitsTheHour_savesTheToggleAndLeavesTheExistingHour() throws Exception {
        UserPreferenceEntity existing = optedInPreference(100L, "Europe/London", 17);
        given(preferenceRepository.findByUserId(100L)).willReturn(Optional.of(existing));

        mockMvc.perform(put("/api/v1/profile/alerts/daily-summary")
                .with(fullAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dailySummaryEnabled\": false, \"timezone\": \"Europe/London\"}"))
                .andExpect(status().isOk());

        verify(preferenceRepository).save(argThat(entity -> Boolean.FALSE.equals(entity.getDailySummaryEnabled())));
        verify(preferenceRepository).save(argThat(entity -> Integer.valueOf(17).equals(entity.getDailySummaryHour())));
    }

    /**
     * The read every new user starts from: no row of their own yet, so the answer is the documented
     * default rather than null - and it is 8, the hour the old global config sent at, so nobody's
     * delivery time moves on the day this column appears. Covers both ways a user can have no hour:
     * no row at all, and a row written before the column existed.
     */
    @Test
    @DisplayName("Block: A user who never chose an hour reads back the default 8 - [MEANT TO PASS]")
    void getPreferences_userNeverChoseAnHour_readsBackTheDefaultHourEight() throws Exception {
        given(preferenceRepository.findByUserId(999L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/internal/profiles/999/preferences")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailySummaryHour").value(8));

        // and the same for a row written before the column existed: still 8, never null, because
        // notification-service compares this against the current hour as an int - a null there is an
        // unboxing failure in the middle of the sweep, not a user who simply never picked a time
        UserPreferenceEntity preMigrationRow = optedInPreference(100L, "UTC", 8);
        preMigrationRow.setDailySummaryHour(null);
        given(preferenceRepository.findByUserId(100L)).willReturn(Optional.of(preMigrationRow));

        mockMvc.perform(get("/api/v1/profile/alerts/me")
                .with(fullAuthToken(100L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailySummaryHour").value(8));

        verify(preferenceRepository, never()).save(any());
    }

    /**
     * The sweep the hourly job now runs. It can no longer work out which timezones are currently at
     * the send hour, because there is no single send hour any more - every zone is a potential match
     * on every pass. So it takes the whole opt-in list in one call and compares each user's own hour
     * itself, instead of asking this service once per zone (~600 requests an hour).
     */
    @Test
    @DisplayName("Block: GET daily-summary-users with no timezone returns every opted-in user - [MEANT TO PASS]")
    void getDailySummaryUsers_timezoneParameterOmitted_returnsEveryOptedInUserAcrossAllZones() throws Exception {
        given(preferenceRepository.findByDailySummaryEnabledTrue()).willReturn(List.of(
                optedInPreference(200L, "America/New_York", 8),
                optedInPreference(300L, "Asia/Tokyo", 23)));

        mockMvc.perform(get("/api/v1/internal/profiles/daily-summary-users")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").value(200))
                .andExpect(jsonPath("$[0].dailySummaryHour").value(8))
                .andExpect(jsonPath("$[1].userId").value(300))
                .andExpect(jsonPath("$[1].timezone").value("Asia/Tokyo"))
                .andExpect(jsonPath("$[1].dailySummaryHour").value(23));

        // genuinely unfiltered - any() rather than anyString() precisely because the failure mode
        // worth catching is the timezone query being run with a null argument, which anyString()
        // does not match and would therefore let through
        verify(preferenceRepository, never()).findByDailySummaryEnabledTrueAndTimezone(any());
    }

    /**
     * The other half of that rule: making the parameter optional must not change what it does when it
     * IS supplied, because notification-service's manual trigger endpoint still sends one zone.
     */
    @Test
    @DisplayName("Block: GET daily-summary-users still filters to a single zone when a timezone is supplied - [MEANT TO PASS]")
    void getDailySummaryUsers_timezoneSupplied_stillRunsTheZoneFilteredQueryRatherThanFetchingEveryone() throws Exception {
        given(preferenceRepository.findByDailySummaryEnabledTrueAndTimezone("America/New_York"))
                .willReturn(List.of(optedInPreference(200L, "America/New_York", 8)));

        mockMvc.perform(get("/api/v1/internal/profiles/daily-summary-users")
                .param("timezone", "America/New_York")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(200))
                .andExpect(jsonPath("$[0].dailySummaryHour").value(8));

        // the filtered query, not "fetch everyone and hope the caller narrows it down"
        verify(preferenceRepository, never()).findByDailySummaryEnabledTrue();
    }

    // ==========================================
    // Internal endpoint shared secret (X-Internal-Token)
    // ==========================================
    //
    // Every endpoint under /api/v1/internal/ used to be reachable by anyone who could route a packet
    // to this service. The k8s ingress declining to route that prefix was the whole defence, which
    // holds right up until an ingress rule is edited wrongly, an SSRF forwards a request, or anything
    // at all is running inside the network. These tests pin the second layer that now stands there.

    /**
     * No header at all - the case an attacker who has not read the contract actually sends.
     */
    @Test
    @DisplayName("Block: Internal endpoint rejects a request with no X-Internal-Token - [MEANT TO FAIL]")
    void internalEndpoint_noInternalTokenHeader_returns401AndNeverReachesTheController() throws Exception {
        mockMvc.perform(get("/api/v1/internal/profiles/100/kyc-status"))
                .andExpect(status().isUnauthorized())
                // both keys, same text: the frontend's extractApiError reads "error" first and
                // "message" second, and the services in this project disagree about which one they
                // send (see GlobalExceptionHandler), so populating both reads correctly either way
                .andExpect(jsonPath("$.error").value("Unauthorized internal request"))
                .andExpect(jsonPath("$.message").value("Unauthorized internal request"));

        // rejected in the filter chain, before the controller - so the lookup never even happened
        verify(userProfileRepository, never()).findById(100L);
    }

    /**
     * A present-but-wrong token is refused exactly like a missing one, and with the identical body -
     * an attacker who could tell "wrong secret" apart from "no secret" would know the header name is
     * right and only the value is left to guess.
     */
    @Test
    @DisplayName("Block: Internal endpoint rejects a request carrying the wrong X-Internal-Token - [MEANT TO FAIL]")
    void internalEndpoint_wrongInternalToken_returns401WithTheSameBodyAsAMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/internal/profiles/100/preferences")
                .header(INTERNAL_TOKEN_HEADER, "not-the-internal-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized internal request"))
                .andExpect(jsonPath("$.message").value("Unauthorized internal request"));

        verify(preferenceRepository, never()).findByUserId(100L);
    }

    /**
     * The other half of the rule: a correctly configured caller is unaffected. The token gates the
     * prefix, it does not change what the endpoints underneath it answer.
     */
    @Test
    @DisplayName("Block: Internal endpoint serves the request normally with the correct X-Internal-Token - [MEANT TO PASS]")
    void internalEndpoint_correctInternalToken_returns200AndServesTheRequestAsBefore() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        mockMvc.perform(get("/api/v1/internal/profiles/100/kyc-status")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
    }

    /**
     * Scoping, from the other direction: the browser never sends this header and never should, so a
     * filter that gated more than /api/v1/internal/ would take the whole customer-facing API down.
     * (The KYC webhook proves the same point for its own prefix - every webhook test above posts
     * without an X-Internal-Token and still expects to be judged purely on its HMAC signature.)
     */
    @Test
    @DisplayName("Block: A customer-facing JWT endpoint still works with no X-Internal-Token header - [MEANT TO PASS]")
    void getMyKycStatus_noInternalTokenHeaderOnACustomerFacingEndpoint_returns200() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        mockMvc.perform(get("/api/v1/profiles/me/kyc-status")
                .with(fullAuthToken(100L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
    }

    /**
     * OUTBOUND. This service calls auth-service's /api/v1/internal/users/{userId}/phone-number on
     * every identity-form submission, and auth-service is adding the same gate. A header missing here
     * fails nothing in this module's own tests - it surfaces as a 401 from someone else's service the
     * day they switch enforcement on, which is why it is asserted directly against a bare
     * {@code RequestTemplate} rather than through the mocked {@code AuthServiceClient}.
     */
    @Test
    @DisplayName("Block: Feign interceptor attaches X-Internal-Token to outbound internal calls - [MEANT TO PASS]")
    void internalTokenRequestInterceptor_anyOutboundRequest_attachesTheConfiguredInternalTokenHeader() {
        RequestTemplate template = new RequestTemplate();

        internalTokenRequestInterceptor.apply(template);

        // the configured value, not the dev default - same property that gates the inbound side, so
        // rotating the secret moves both directions at once
        assertThat(template.headers().get(INTERNAL_TOKEN_HEADER)).containsExactly(INTERNAL_TOKEN);
    }

    // ==========================================
    // UserRegisteredListener (provisions a profile for a brand-new auth-service user)
    // ==========================================

    @Test
    @DisplayName("UserRegistered event creates a PENDING_VERIFICATION profile for a new user id - [MEANT TO PASS]")
    void consumeUserRegistered_noProfileExistsForThatId_savesAPendingVerificationProfile() {
        given(userProfileRepository.existsById(500L)).willReturn(false);
        // userId arrives as a String on the wire, so the listener parsing it back to a Long is part
        // of what the argThat below is checking
        Map<String, Object> event = Map.of(
                "userId", "500",
                "username", "newuser",
                "phoneNumber", "+15559876543"
        );

        userRegisteredListener.consumeUserRegistered(event);

        verify(userProfileRepository).save(argThat(profile ->
                profile.getId().equals(500L)
                        && profile.getPhoneNumber().equals("+15559876543")
                        && profile.getKycStatus() == KycStatus.PENDING_VERIFICATION
        ));
    }

    /**
     * Kafka redelivers, so this listener sees the same registration more than once. A second pass
     * must not overwrite a profile the user has since filled in and had approved.
     */
    @Test
    @DisplayName("UserRegistered event is a no-op if a profile already exists for that id - [MEANT TO PASS]")
    void consumeUserRegistered_profileAlreadyExistsForThatId_savesNothing() {
        given(userProfileRepository.existsById(500L)).willReturn(true);
        Map<String, Object> event = Map.of(
                "userId", "500",
                "username", "newuser",
                "phoneNumber", "+15559876543"
        );

        userRegisteredListener.consumeUserRegistered(event);

        verify(userProfileRepository, never()).save(any(UserProfile.class));
    }

    // The two tokens auth-service actually issues, built the way it builds them (see its JwtService):
    // a completed login carries scope=FULL_AUTH, which the resource server's default converter turns
    // into the SCOPE_FULL_AUTH authority every customer-facing endpoint here checks for. Both forms
    // exist as helpers so a test's intent - "a finished session" vs "a password-only session" - is
    // readable at the call site rather than inferred from which claims were set.
    private static JwtRequestPostProcessor fullAuthToken(long userId) {
        return jwt().jwt(builder -> builder
                .claim("userId", userId)
                .claim("token_type", "FULL_AUTH")
                .claim("scope", "FULL_AUTH"));
    }

    // The token handed out after a correct password but BEFORE the 2FA code is submitted. auth-service
    // deliberately puts no scope claim on it, so it arrives here carrying no authorities at all - the
    // authorities are emptied explicitly rather than left to the test default, which would otherwise
    // grant a SCOPE_read this token never actually has.
    private static JwtRequestPostProcessor preAuthToken(long userId) {
        return jwt().jwt(builder -> builder
                        .claim("userId", userId)
                        .claim("token_type", "PRE_AUTH"))
                .authorities(List.<GrantedAuthority>of());
    }

    // a complete, otherwise-valid identity submission - only the phone number varies, since that is
    // the field every test in the ownership block is actually about
    private UpdateContactInfoRequestDto identityDtoWithPhone(String phoneNumber) {
        UpdateContactInfoRequestDto dto = new UpdateContactInfoRequestDto();
        dto.setLegalName("Jane Q Public");
        dto.setDateOfBirth(LocalDate.of(1990, 4, 17));
        dto.setPhoneNumber(phoneNumber);
        dto.setAddressLine1("456 Innovation Blvd");
        dto.setCity("San Jose");
        dto.setState("CA");
        dto.setZipCode("95110");
        return dto;
    }

    // an opted-in preference row: the daily-summary tests differ only in whose it is, which zone they
    // are in and which hour they picked, so everything else is filled in with the documented defaults
    private UserPreferenceEntity optedInPreference(long userId, String timezone, int dailySummaryHour) {
        UserPreferenceEntity entity = new UserPreferenceEntity();
        entity.setUserId(userId);
        entity.setAlertThresholdAmount(new BigDecimal("100.00"));
        entity.setDailySummaryEnabled(true);
        entity.setTimezone(timezone);
        entity.setDailySummaryHour(dailySummaryHour);
        return entity;
    }

    private String calculateHmac(String data, String key) {
        try {
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secretKeySpec);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC", e);
        }
    }
}
