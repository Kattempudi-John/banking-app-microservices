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

    // shared fixture that runs before every test, builds one baseline user profile to reuse
    // I give it a real id, phone number and address so downstream code has real looking data to work with
    // kyc status starts out pending since that is the default state a brand new profile should be in
    // individual tests below are free to mutate this mockUser further before their own assertions run
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

    // a user can hold valid credentials in auth-service and still have no profile row here, if the
    // "user-events" message that normally provisions one was never consumed
    // stub the repository so looking up id 999 comes back completely empty, and echo back whatever
    // gets saved so the controller can read a status off it
    // this used to answer 500, which quietly blocked every transfer that user attempted, since
    // transaction-service's KycEnforcementAspect calls this same endpoint before moving any money
    // now it provisions the missing profile on read and reports the PENDING_VERIFICATION it starts in
    @Test
    @DisplayName("Block 1: Query KYC Status Provisions a Missing Profile Instead of Failing - [MEANT TO PASS]")
    void testBlock1_GetKycStatus_UserNotFound_ProvisionsProfile() throws Exception {
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

    // happy path check that the kyc-status endpoint reports back whatever state the user is actually in
    // stub the repository so looking up user 100 returns our fixture user from setUp
    // hit the kyc-status endpoint for that user
    // expect a 200 ok and the status field to say pending_verification, matching the fixture
    @Test
    @DisplayName("Final Block: Acceptance Criteria Verification - Query Active KYC Status - [MEANT TO PASS]")
    void testFinalAC_GetKycStatus_ReturnsCurrentPendingState() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        mockMvc.perform(get("/api/v1/internal/profiles/100/kyc-status")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
    }

    // the endpoint the frontend calls: it takes no userId at all, so there is no id for a caller to
    // tamper with - the status returned is always the one belonging to the token's own user
    @Test
    @DisplayName("Block 1b: /profiles/me/kyc-status Reads The Caller's Own Id From The JWT - [MEANT TO PASS]")
    void testGetMyKycStatus_UsesTokenUserId() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        mockMvc.perform(get("/api/v1/profiles/me/kyc-status")
                .with(jwt().jwt(builder -> builder.claim("userId", 100L))
                        .authorities(new SimpleGrantedAuthority("SCOPE_FULL_AUTH"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
    }

    // this is the whole reason the endpoint moved: it used to be permitAll AND sat under a path the
    // k8s ingress publishes, so anyone on the internet could read any user's KYC status by id
    @Test
    @DisplayName("Block 1c: /profiles/me/kyc-status Rejects An Unauthenticated Caller - [MEANT TO FAIL]")
    void testGetMyKycStatus_RequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/me/kyc-status"))
                .andExpect(status().isUnauthorized());
    }

    // making sure the kyc webhook refuses a request with no signature header at all
    // build a normal looking webhook payload for a user being approved
    // post it straight to the webhook endpoint without adding the x-signature header
    // it should be rejected with 401 unauthorized
    // and the error message should call out specifically that the signature header is missing
    @Test
    @DisplayName("Block 1: Webhook Rejects Request Missing HMAC Signature Header - [MEANT TO FAIL]")
    void testBlock1_Webhook_MissingSignature_Returns401() throws Exception {
        String payloadJson = objectMapper.writeValueAsString(Map.of("userId", "100", "status", "APPROVED"));

        mockMvc.perform(post("/api/v1/webhooks/kyc-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payloadJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing X-Signature header"));
    }

    // similar to the last test but this time a signature header is present, it is just wrong
    // build the same kind of webhook payload as before
    // post it along with a made up x-signature value that was never actually computed with the real key
    // it should still be rejected with 401 unauthorized
    // and the error message this time should say the signature itself is invalid, not missing
    @Test
    @DisplayName("Block 2: Webhook Rejects Invalid HMAC Signature - [MEANT TO FAIL]")
    void testBlock2_Webhook_InvalidSignature_Returns401() throws Exception {
        String payloadJson = objectMapper.writeValueAsString(Map.of("userId", "100", "status", "APPROVED"));

        mockMvc.perform(post("/api/v1/webhooks/kyc-update")
                .header("X-Signature", "InvalidSignatureValue123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payloadJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid webhook signature"));
    }

    // happy path for the webhook, this time the signature is computed correctly
    // stub the repository to return our fixture user so there is something to update
    // build the payload then run it through the same hmac sha256 algorithm the real vendor would use
    // post the payload along with that correctly computed signature header
    // expect a plain 200 ok back
    // then confirm the user's kyc status actually flipped to approved and got saved
    @Test
    @DisplayName("Final Block: Acceptance Criteria Verification - Valid Webhook Updates KYC to APPROVED - [MEANT TO PASS]")
    void testFinalAC_Webhook_ValidSignature_UpdatesKycToApproved() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        String payloadJson = objectMapper.writeValueAsString(Map.of("userId", "100", "status", "APPROVED"));
        String validHmac = calculateHmac(payloadJson, "SuperSecretVendorKey123!");

        mockMvc.perform(post("/api/v1/webhooks/kyc-update")
                .header("X-Signature", validHmac)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payloadJson))
                .andExpect(status().isOk());

        assertThat(mockUser.getKycStatus()).isEqualTo(KycStatus.APPROVED);
        verify(userProfileRepository).save(mockUser);
    }

    // checking that re-processing the same status does not spam out a duplicate kafka event
    // set the fixture user's kyc status to approved already, like the webhook already ran once before
    // call processkycwebhook again with that exact same approved status
    // since nothing actually changed, kafkatemplate.send should never get called at all
    @Test
    @DisplayName("Block 1: Process Webhook Status Unchanged Does Not Broadcast Kafka Event - [MEANT TO PASS]")
    void testBlock1_ProcessWebhook_StatusUnchanged_IdempotentNoKafkaEvent() {
        mockUser.setKycStatus(KycStatus.APPROVED);
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        profileManagementService.processKycWebhook(100L, KycStatus.APPROVED);

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    // this time the status is actually changing so a kafka event should go out
    // fixture user starts out pending from setUp, so approving it is a real transition
    // call processkycwebhook with approved as the new status
    // verify the user profile got saved with the new status
    // and verify a kycstatusupdatedevent was published to the kyc-events topic keyed by user id
    @Test
    @DisplayName("Final Block: Acceptance Criteria Verification - KYC Status Transition Broadcasts Kafka Event - [MEANT TO PASS]")
    void testFinalAC_ProcessWebhook_StatusChanged_PublishesKycStatusUpdatedEvent() {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        profileManagementService.processKycWebhook(100L, KycStatus.APPROVED);

        verify(userProfileRepository).save(mockUser);
        verify(kafkaTemplate).send(eq("kyc-events"), eq("100"), any(KycStatusUpdatedEvent.class));
    }

    // making sure a compliance officer cannot manually override kyc status without giving a real reason
    // withmockuser here simulates a logged in compliance officer, user id 500 with the right role
    // build a request where the reason field is just blank spaces, not an actual explanation
    // patch it to the admin kyc override endpoint
    // expect a 400 bad request with an error saying the override reason is mandatory
    @Test
    @DisplayName("Block 1: Admin Override Fails When Reason Text is Blank - [MEANT TO FAIL]")
    void testBlock1_AdminOverride_MissingReason_ReturnsBadRequest() throws Exception {
        String requestJson = objectMapper.writeValueAsString(Map.of("status", "APPROVED", "reason", "  "));

        mockMvc.perform(patch("/api/v1/admin/profiles/100/kyc")
                .with(jwt().jwt(j -> j.claim("userId", 500L)).authorities(new SimpleGrantedAuthority("ROLE_COMPLIANCE_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Override reason is mandatory"));
    }

    // happy path for a compliance officer manually overriding a user's kyc status with a real reason
    // stub the repository to return the fixture user so there is something to actually update
    // this time give a real reason string, manual verification of a physical passport
    // patch that request to the admin override endpoint as the mocked compliance officer
    // expect 200 ok with a message confirming the manual override happened
    // then confirm three separate side effects, an audit log entry got saved, the profile got saved,
    // and a kafka event went out on kyc-events so other services hear about the change
    @Test
    @DisplayName("Final Block: Acceptance Criteria Verification - Admin Override Updates DB, Audits, and Broadcasts Kafka Event - [MEANT TO PASS]")
    void testFinalAC_AdminOverride_ValidRequest_SavesAuditLogAndPublishesKafka() throws Exception {
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

    // checking validation on the contact info update endpoint rejects a bad phone number format
    // withmockuser simulates the logged in owner of profile 100 making this request themselves
    // build a dto with an obviously invalid phone value alongside otherwise normal address fields
    // put that dto to the contact-info endpoint
    // expect a 400 bad request since the phone number does not match the expected international format
    @Test
    @DisplayName("Block 1: Contact Info Update Rejects Invalid International Phone Format - [MEANT TO FAIL]")
    @WithMockUser(username = "100")
    void testBlock1_UpdateContactInfo_InvalidPhoneNumber_ReturnsBadRequest() throws Exception {
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

    // happy path for updating contact info with an actually valid dto
    // stub the repository so user 100 resolves to our fixture profile
    // build a dto with a properly formatted phone number and a new address
    // put that to the contact-info endpoint as the same logged in user
    // expect 200 ok with a profile updated successfully message
    // then confirm the in memory mockUser object itself picked up the new phone and address
    // and that the repository actually got told to save it
    @Test
    @DisplayName("Final Block: Acceptance Criteria Verification - Valid Contact Info Update Persists to Database - [MEANT TO PASS]")
    void testFinalAC_UpdateContactInfo_ValidDto_SavesUserProfile() throws Exception {
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

    // the counterpart to the above - a REJECTED applicant editing their details must not be able to
    // clear their own rejection, that call belongs to the vendor or a compliance officer's override
    @Test
    @DisplayName("Block: Contact Info Update Does Not Re-Approve A REJECTED Applicant - [MEANT TO PASS]")
    void testUpdateContactInfo_RejectedApplicant_IsNotAutoApproved() throws Exception {
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

    // an applicant under the minimum age is rejected outright rather than saved and left silently
    // unverified, which would leave them resubmitting the same form wondering why transfers are blocked
    @Test
    @DisplayName("Block: Contact Info Update Rejects An Applicant Under 18 - [MEANT TO FAIL]")
    void testUpdateContactInfo_UnderageApplicant_ReturnsBadRequest() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        UpdateContactInfoRequestDto dto = new UpdateContactInfoRequestDto();
        dto.setLegalName("Too Young");
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

    // a legal name and date of birth are what make this a verification rather than an address change,
    // so the endpoint must not accept a submission missing either
    @Test
    @DisplayName("Block: Contact Info Update Rejects A Missing Legal Name And Date Of Birth - [MEANT TO FAIL]")
    void testUpdateContactInfo_MissingIdentityFields_ReturnsBadRequest() throws Exception {
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

    // The account-takeover this endpoint's @PreAuthorize exists to stop, reproduced end to end: log in
    // with a stolen password from an unrecognised device, take the PRE_AUTH token auth-service answers
    // with instead of finishing 2FA, and put a new phone number on the identity form. It used to
    // answer 200 APPROVED and write the attacker's number through to auth-service's users table -
    // which is where 2FA codes are delivered from, so the second factor moved to the attacker and the
    // password alone became the whole account.
    @Test
    @DisplayName("Block: PUT contact-info Is Forbidden To A PRE_AUTH Token And Never Moves The 2FA Phone Number - [MEANT TO FAIL]")
    void testUpdateContactInfo_PreAuthTokenBeforeTwoFactor_IsForbiddenAndChangesNothing() throws Exception {
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

    // the other half of that rule: the fix is a check on the session, not on the endpoint. A user who
    // did finish 2FA still submits this form exactly as before.
    @Test
    @DisplayName("Block: PUT contact-info Still Succeeds Once 2FA Is Complete (FULL_AUTH Token) - [MEANT TO PASS]")
    void testUpdateContactInfo_FullAuthTokenAfterTwoFactor_StillSucceeds() throws Exception {
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
    void testGetMyContactInfo_PreAuthToken_IsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/me/contact-info")
                .with(preAuthToken(100L)))
                .andExpect(status().isForbidden());

        verify(authServiceClient, never()).getPhoneNumber(any());
    }

    @Test
    @DisplayName("Block: GET /profiles/me/kyc-status Is Forbidden To A PRE_AUTH Token - [MEANT TO FAIL]")
    void testGetMyKycStatus_PreAuthToken_IsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/me/kyc-status")
                .with(preAuthToken(100L)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Block: Alert preference writes are forbidden to a PRE_AUTH token - [MEANT TO FAIL]")
    void testUpdateAlertThreshold_PreAuthToken_IsForbidden() throws Exception {
        UpdateAlertThresholdRequestDto dto = new UpdateAlertThresholdRequestDto(new BigDecimal("250.00"));

        mockMvc.perform(put("/api/v1/profile/alerts/threshold")
                .with(preAuthToken(100L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());

        verify(preferenceRepository, never()).save(any());
    }

    // this response carries the user's email address, so a session that has not finished proving who
    // it is does not get to read it
    @Test
    @DisplayName("Block: GET alerts/me Is Forbidden To A PRE_AUTH Token - [MEANT TO FAIL]")
    void testGetMyPreferences_PreAuthToken_IsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/profile/alerts/me")
                .with(preAuthToken(100L)))
                .andExpect(status().isForbidden());
    }

    // ==========================================
    // Phone number ownership (auth-service is the owner, this service mirrors)
    // ==========================================

    // the bug this whole change exists for: signup already answers 409 for a number someone else
    // holds, but this form used to write the number straight into the local table with no check at
    // all, so it was a way around that rule - and it handed out a KYC approval on the way past
    @Test
    @DisplayName("Block: Contact Info Update Is Rejected When The Phone Number Belongs To Another User - [MEANT TO FAIL]")
    void testUpdateContactInfo_PhoneNumberHeldByAnotherUser_ReturnsConflictAndPersistsNothing() throws Exception {
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

    // the other half of that rule: your own unchanged number is not a conflict, otherwise nobody
    // could ever correct their address without also being forced to change their phone number
    @Test
    @DisplayName("Block: Re-Submitting Your Own Unchanged Phone Number Succeeds - [MEANT TO PASS]")
    void testUpdateContactInfo_ResubmittingOwnPhoneNumber_Succeeds() throws Exception {
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

    // the stored value is whatever auth-service answered with, not the result of normalizing the
    // input a second time here - one owner of the format, so the two copies cannot drift
    @Test
    @DisplayName("Block: Stored Phone Number Is The E.164 Value auth-service Returned - [MEANT TO PASS]")
    void testUpdateContactInfo_StoresTheNormalizedNumberAuthServiceReturned() throws Exception {
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

    // auth-service's own wording for an unusable number is passed straight through, so the user is
    // told the same thing here as they would be told at signup
    @Test
    @DisplayName("Block: Contact Info Update Surfaces auth-service's 400 For An Unresolvable Number - [MEANT TO FAIL]")
    void testUpdateContactInfo_AuthServiceRejectsNumber_ReturnsBadRequest() throws Exception {
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

    // a connection failure reaches feign as a RetryableException, never touching the error decoder -
    // the point of this test is that "auth-service is down" fails the submission rather than falling
    // back to saving locally, which would approve a user on a number nobody ever verified
    @Test
    @DisplayName("Block: auth-service Being Unreachable Does Not Save Or Approve Anything - [MEANT TO FAIL]")
    void testUpdateContactInfo_AuthServiceUnreachable_DoesNotApprove() throws Exception {
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

    // takes no userId at all, same as the kyc-status endpoint next to it - the fields returned are
    // always the token's own user's, and the phone number is auth-service's copy, not the mirror
    @Test
    @DisplayName("Block: GET contact-info Returns The Caller's Own Fields - [MEANT TO PASS]")
    void testGetMyContactInfo_ReturnsCallersFields() throws Exception {
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
                .andExpect(jsonPath("$.phoneNumber").value("+15712856947"))
                .andExpect(jsonPath("$.addressLine1").value("123 Financial Way"))
                .andExpect(jsonPath("$.addressLine2").value("Apt 4B"))
                .andExpect(jsonPath("$.city").value("New York"))
                .andExpect(jsonPath("$.state").value("NY"))
                .andExpect(jsonPath("$.zipCode").value("10001"));
    }

    // an auth-service outage must not blank the form out - a blank form is what gets retyped wrong,
    // which is the behaviour that created the duplicate numbers in the first place
    @Test
    @DisplayName("Block: GET contact-info Falls Back To The Local Phone Copy When auth-service Is Down - [MEANT TO PASS]")
    void testGetMyContactInfo_AuthServiceUnreachable_FallsBackToLocalCopy() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));
        willThrow(new RuntimeException("Connection refused: localhost/127.0.0.1:8081"))
                .given(authServiceClient).getPhoneNumber(100L);

        mockMvc.perform(get("/api/v1/profiles/me/contact-info")
                .with(jwt().jwt(builder -> builder.claim("userId", 100L))
                        .authorities(new SimpleGrantedAuthority("SCOPE_FULL_AUTH"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+14155552671"));
    }

    // same reasoning as the kyc-status endpoint: this response carries a legal name and date of
    // birth, so an unauthenticated caller gets nothing
    @Test
    @DisplayName("Block: GET contact-info Rejects An Unauthenticated Caller - [MEANT TO FAIL]")
    void testGetMyContactInfo_RequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/me/contact-info"))
                .andExpect(status().isUnauthorized());
    }

    // this one calls the service layer directly instead of going through mockmvc
    // stub the repository so user 100 resolves to the fixture profile
    // build a contact info dto with new values and call updatecontactinfo on the service
    // afterward verify a profileupdatedevent got published to the profile-events topic keyed by user id
    // this is the event the audit service and notification service both end up listening for downstream
    @Test
    @DisplayName("Final Block: Acceptance Criteria Verification - Profile Update Publishes ProfileUpdatedEvent to Kafka - [MEANT TO PASS]")
    void testFinalAC_UpdateContactInfo_PublishesProfileUpdatedEventToKafka() {
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

    // checking the basic happy path for setting an alert threshold for the first time
    // withmockuser here includes the scope_full_auth authority since this endpoint requires a full session
    // stub the preference repository so this user has no existing preference row yet
    // put a simple dto with just a dollar amount to the threshold endpoint
    // expect 200 ok back
    // then confirm the saved entity has the right user id and the right threshold amount attached
    @Test
    @DisplayName("Block: Update alert threshold with a valid payload persists the preference - [MEANT TO PASS]")
    void testBlock_UpdateAlertThreshold_ValidPayload_Persists() throws Exception {
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

    // confirming the threshold endpoint no longer needs the daily summary fields tagging along
    // stub the repository so this user has no existing preference row saved yet
    // send a raw json payload with only the alertthresholdamount key and nothing else
    // expect 200 ok since the dto for this endpoint is now split from the daily summary one
    // then check that a brand new user still gets sane defaults for the untouched fields,
    // a hundred dollar default is not what we check here, just that summary is off and timezone is utc
    @Test
    @DisplayName("Block (fixed): Threshold-only payload is sufficient - daily-summary fields are no longer required - [MEANT TO PASS]")
    void testBlock_UpdateAlertThreshold_ThresholdOnlyPayload_NoLongerRequiresDailySummaryFields() throws Exception {
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

    // making sure updating just the threshold does not clobber daily summary settings someone already set
    // build an existing preference entity by hand with daily summary already turned on and a real timezone
    // stub the repository to return that existing row when this user is looked up
    // put a new threshold value to the threshold endpoint
    // expect 200 ok
    // then confirm the new threshold saved but the daily summary flag and timezone are exactly as before
    @Test
    @DisplayName("Block: Updating the threshold leaves an existing daily-summary preference untouched - [MEANT TO PASS]")
    void testBlock_UpdateAlertThreshold_PreservesExistingDailySummarySettings() throws Exception {
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

    // checking that a made up timezone string gets rejected instead of silently accepted
    // build a daily summary dto with enabled true but a timezone value that is not a real iana zone
    // put that to the daily-summary endpoint
    // expect a 400 bad request since the timezone has to be a real identifier like america/new_york
    @Test
    @DisplayName("Block: Invalid IANA timezone identifier is rejected - [MEANT TO FAIL]")
    void testBlock_UpdateDailySummary_InvalidTimezone_ReturnsBadRequest() throws Exception {
        UpdateDailySummaryRequestDto dto = new UpdateDailySummaryRequestDto(true, "Not/A_Real_Zone");

        mockMvc.perform(put("/api/v1/profile/alerts/daily-summary")
                .with(jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", 100L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    // happy path for opting into the daily balance summary email with a real timezone
    // stub the repository so this user has no existing preference row yet
    // build a dto with enabled true and a real iana timezone, europe/london
    // put that to the daily-summary endpoint
    // expect 200 ok, and this endpoint intentionally returns a plain string not json, so we check
    // that the jsonpath for a message field simply does not exist rather than expecting real json
    // last confirm the saved entity has the right user id, enabled flag and timezone
    @Test
    @DisplayName("Final Block: Acceptance Criteria Verification - Daily summary opt-in persists enabled flag and timezone - [MEANT TO PASS]")
    void testFinalAC_UpdateDailySummarySettings_ValidPayload_Persists() throws Exception {
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

    // the mirror image of the earlier test, this time daily summary changes should not touch the threshold
    // build an existing preference entity by hand with a real threshold value already set
    // stub the repository to return that existing row for this user
    // put new daily summary settings, enabled true with a different timezone, asia/tokyo
    // expect 200 ok
    // then confirm the threshold amount saved is untouched while summary and timezone did update
    @Test
    @DisplayName("Block: Updating daily-summary settings leaves an existing alert threshold untouched - [MEANT TO PASS]")
    void testBlock_UpdateDailySummary_PreservesExistingAlertThreshold() throws Exception {
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

    // checking that hitting the alert preference endpoints without a proper session is rejected
    // notice there is no withmockuser annotation on this one at all, unlike the other preference tests
    // build a normal looking threshold dto anyway
    // put it to the threshold endpoint with no authentication attached
    // expect some kind of 4xx client error back, matching the class level preauthorize check on the controller
    @Test
    @DisplayName("Block: Pre-Auth token is denied on alert preference endpoints - [MEANT TO FAIL]")
    void testBlock_AlertPreferences_Unauthenticated_Denied() throws Exception {
        UpdateAlertThresholdRequestDto dto = new UpdateAlertThresholdRequestDto(new BigDecimal("100.00"));

        mockMvc.perform(put("/api/v1/profile/alerts/threshold")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().is4xxClientError());
    }

    // the endpoint the Alert Preferences page calls - authenticated, and scoped to the token's own
    // user, so nobody can read another person's threshold or email by changing an id
    @Test
    @DisplayName("Block: GET alerts/me returns the caller's own preferences - [MEANT TO PASS]")
    void testBlock_GetMyPreferences_UsesTokenUserId() throws Exception {
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

    // the whole reason the service-to-service reads moved to /api/v1/internal/: now that the ingress
    // routes /api/v1/profile, anything left unauthenticated under it would be public - and this
    // response carries the user's email address
    @Test
    @DisplayName("Block: GET alerts/me Rejects An Unauthenticated Caller - [MEANT TO FAIL]")
    void testBlock_GetMyPreferences_RequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/profile/alerts/me"))
                .andExpect(status().isUnauthorized());
    }

    // happy path: notification-service asks for a user's preferences and gets back exactly
    // what's stored - no end-user JWT, since this is a service-to-service call, just the shared
    // internal token that replaced "the ingress doesn't route this prefix" as the only protection
    @Test
    @DisplayName("Block: GET internal preferences returns the stored values without an end-user JWT - [MEANT TO PASS]")
    void testBlock_GetPreferences_ExistingRow_ReturnsStoredValues() throws Exception {
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

    // a user with no preference row yet should still get a coherent response (the documented
    // defaults), not a 404 - and this lookup must never persist anything on its own
    @Test
    @DisplayName("Block: GET internal preferences returns documented defaults for a user with no row yet - [MEANT TO PASS]")
    void testBlock_GetPreferences_NoRow_ReturnsDefaultsWithoutPersisting() throws Exception {
        given(preferenceRepository.findByUserId(999L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/internal/profiles/999/preferences")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertThresholdAmount").value(100.00))
                .andExpect(jsonPath("$.dailySummaryEnabled").value(false))
                .andExpect(jsonPath("$.timezone").value("UTC"));

        verify(preferenceRepository, never()).save(any());
    }

    // the daily-summary batch job asks for every user opted in for one specific timezone - confirm
    // the response shape, and that it's reachable on the internal token alone (there is no user in
    // the room on this sweep at all, so there is no JWT it could ever send)
    @Test
    @DisplayName("Block: GET daily-summary-users filters by timezone and opt-in flag - [MEANT TO PASS]")
    void testBlock_GetUsersForDailySummary_ReturnsOptedInUsersForTimezone() throws Exception {
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
    void testUpdateDailySummary_ChosenHour_PersistsAndReadsBackOnBothResponses() throws Exception {
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

    // both ends of the range, because an off-by-one in the bounds silently deletes an hour a user can
    // actually pick - either midnight or 11pm, depending on which end got it wrong
    @Test
    @DisplayName("Block: Hour 0 and hour 23 are both accepted - [MEANT TO PASS]")
    void testUpdateDailySummary_BoundaryHours_AreBothAccepted() throws Exception {
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

    // 24 is the plausible typo for midnight and -1 for "an hour earlier". Neither may be quietly
    // clamped to 23 or 0 and stored: the user would be told their choice was saved and then be
    // emailed at a different time than the one showing on their own preferences page.
    @Test
    @DisplayName("Block: An hour outside 0-23 is rejected with a readable 400 and nothing is saved - [MEANT TO FAIL]")
    void testUpdateDailySummary_OutOfRangeHour_ReturnsBadRequestAndSavesNothing() throws Exception {
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

    // a payload that never mentions the hour is not a request to reset it - a client that only knows
    // how to flip the toggle must not silently drag every user it touches back to 8am
    @Test
    @DisplayName("Block: A daily-summary payload with no hour leaves the user's existing hour alone - [MEANT TO PASS]")
    void testUpdateDailySummary_HourOmitted_LeavesExistingHourUntouched() throws Exception {
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

    // the read every new user starts from: no row of their own yet, so the answer is the documented
    // default rather than null, and it is 8 - the hour the old global config sent at - so nobody's
    // delivery time moves on the day this column appears
    @Test
    @DisplayName("Block: A user who never chose an hour reads back the default 8 - [MEANT TO PASS]")
    void testGetPreferences_HourNeverChosen_ReadsBackEight() throws Exception {
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

    // The sweep the hourly job now runs. It can no longer work out which timezones are currently at
    // the send hour, because there is no single send hour any more - every zone is a potential match
    // on every pass. So it takes the whole opt-in list in one call and compares each user's own hour
    // itself, instead of asking this service once per zone (~600 requests an hour).
    @Test
    @DisplayName("Block: GET daily-summary-users with no timezone returns every opted-in user - [MEANT TO PASS]")
    void testGetUsersForDailySummary_TimezoneOmitted_ReturnsAllOptedInUsers() throws Exception {
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

    // the other half of that rule: making the parameter optional must not change what it does when
    // it IS supplied, because notification-service's manual trigger endpoint still sends one zone
    @Test
    @DisplayName("Block: GET daily-summary-users still filters to a single zone when a timezone is supplied - [MEANT TO PASS]")
    void testGetUsersForDailySummary_TimezoneSupplied_StillFiltersToThatZone() throws Exception {
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

    // no header at all - the case an attacker who has not read the contract actually sends
    @Test
    @DisplayName("Block: Internal endpoint rejects a request with no X-Internal-Token - [MEANT TO FAIL]")
    void testInternalEndpoint_NoToken_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/internal/profiles/100/kyc-status"))
                .andExpect(status().isUnauthorized())
                // both keys, same text: the frontend's extractApiError reads "error" first and
                // "message" second, and the services in this project disagree about which one they
                // send (see GlobalExceptionHandler), so populating both reads correctly either way
                .andExpect(jsonPath("$.error").value("Unauthorized internal request"))
                .andExpect(jsonPath("$.message").value("Unauthorized internal request"));

        // rejected before the controller, so the lookup never even happened
        verify(userProfileRepository, never()).findById(100L);
    }

    // a present-but-wrong token is refused exactly like a missing one, and with the identical body -
    // an attacker who could tell "wrong secret" apart from "no secret" would know the header name is
    // right and only the value is left to guess
    @Test
    @DisplayName("Block: Internal endpoint rejects a request carrying the wrong X-Internal-Token - [MEANT TO FAIL]")
    void testInternalEndpoint_WrongToken_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/internal/profiles/100/preferences")
                .header(INTERNAL_TOKEN_HEADER, "not-the-internal-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized internal request"))
                .andExpect(jsonPath("$.message").value("Unauthorized internal request"));

        verify(preferenceRepository, never()).findByUserId(100L);
    }

    // the other half of the rule: a correctly configured caller is unaffected. The token gates the
    // prefix, it does not change what the endpoints underneath it answer.
    @Test
    @DisplayName("Block: Internal endpoint serves the request normally with the correct X-Internal-Token - [MEANT TO PASS]")
    void testInternalEndpoint_CorrectToken_ServesRequestAsBefore() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        mockMvc.perform(get("/api/v1/internal/profiles/100/kyc-status")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
    }

    // scoping, from the other direction: the browser never sends this header and never should, so a
    // filter that gated more than /api/v1/internal/ would take the whole customer-facing API down.
    // (The KYC webhook proves the same point for its own prefix - every webhook test above posts
    // without an X-Internal-Token and still expects to be judged purely on its HMAC signature.)
    @Test
    @DisplayName("Block: A customer-facing JWT endpoint still works with no X-Internal-Token header - [MEANT TO PASS]")
    void testCustomerFacingEndpoint_WithoutInternalToken_IsUnaffected() throws Exception {
        given(userProfileRepository.findById(100L)).willReturn(Optional.of(mockUser));

        mockMvc.perform(get("/api/v1/profiles/me/kyc-status")
                .with(fullAuthToken(100L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
    }

    // OUTBOUND. This service calls auth-service's /api/v1/internal/users/{userId}/phone-number on
    // every identity-form submission, and auth-service is adding the same gate. A header missing here
    // fails nothing in this module's own tests - it surfaces as a 401 from someone else's service the
    // day they switch enforcement on, which is why it is asserted directly.
    @Test
    @DisplayName("Block: Feign interceptor attaches X-Internal-Token to outbound internal calls - [MEANT TO PASS]")
    void testFeignInterceptor_AttachesInternalTokenHeader() {
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
    void testUserRegisteredListener_NewUser_CreatesPendingProfile() {
        given(userProfileRepository.existsById(500L)).willReturn(false);
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

    @Test
    @DisplayName("UserRegistered event is a no-op if a profile already exists for that id - [MEANT TO PASS]")
    void testUserRegisteredListener_ExistingUser_DoesNotOverwriteProfile() {
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