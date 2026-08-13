package com.example.notificationservice;

import com.example.notificationservice.client.AccountServiceClient;
import com.example.notificationservice.client.AuthServiceClient;
import com.example.notificationservice.client.ProfileServiceClient;
import com.example.notificationservice.event.FundsTransferredEvent;
import com.example.notificationservice.model.NotificationChannel;
import org.mockito.ArgumentCaptor;
import com.example.notificationservice.model.NotificationRecord;
import com.example.notificationservice.model.NotificationStatus;
import com.example.notificationservice.model.NotificationType;
import com.example.notificationservice.repository.NotificationRecordRepository;
import com.example.notificationservice.service.NotificationProviderService;
import com.example.notificationservice.service.ProfileNotificationListener;
import com.example.notificationservice.service.TransactionAlertListener;
import com.example.notificationservice.service.TwoFactorSmsListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Covers the persistence half of the Notifications feature (each listener writing a
// NotificationRecord alongside its existing dispatch call) and the new GET /api/v1/notifications
// endpoint - this service's first-ever REST API. NotificationAlertsTestSuite/
// NotificationProviderServiceTestSuite already cover dispatch/threshold/retry behavior in depth;
// this suite focuses on the new durable-record and read-API behavior layered on top.
@SpringBootTest
@AutoConfigureMockMvc
class NotificationPersistenceTestSuite {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TwoFactorSmsListener twoFactorSmsListener;

    @Autowired
    private TransactionAlertListener transactionAlertListener;

    @Autowired
    private ProfileNotificationListener profileNotificationListener;

    @MockBean
    private NotificationProviderService notificationProviderService;

    @MockBean
    private NotificationRecordRepository notificationRecordRepository;

    @MockBean
    private ProfileServiceClient profileServiceClient;

    // Both mocked so the counterparty lookups a transaction alert now makes stay inside the test.
    // Left real, these are Feign clients pointed at localhost - the suite would either make live HTTP
    // calls to whichever services happen to be running or depend on their being down, and "who owns
    // account 2" would answer differently on a developer's machine than in CI.
    @MockBean
    private AccountServiceClient accountServiceClient;

    @MockBean
    private AuthServiceClient authServiceClient;

    private static RequestPostProcessor fullAuthUser(long userId) {
        return jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", userId));
    }

    @Test
    @DisplayName("2FA SMS dispatch success persists a SENT notification record - [MEANT TO PASS]")
    void testTwoFactorSms_Success_PersistsSentRecord() {
        given(notificationProviderService.dispatchSms(any(), any())).willReturn(true);
        Map<String, Object> event = Map.of(
                "action", "SMS_2FA_REQUESTED", "userId", "42", "phoneNumber", "+15551234567", "code", "123456");

        twoFactorSmsListener.consumeSmsRequest(event);

        verify(notificationRecordRepository).save(argThat(record ->
                record.getUserId().equals(42L)
                        && record.getType() == NotificationType.SMS_2FA
                        && record.getChannel() == NotificationChannel.SMS
                        && record.getStatus() == NotificationStatus.SENT
        ));
    }

    // The record used to store the SMS body verbatim, which put a live one-time code into
    // GET /api/v1/notifications - readable off the notifications page long after the login it
    // belonged to. The SMS itself still has to carry the code; the audit row must not.
    @Test
    @DisplayName("2FA record stores a masked line, never the code itself - [MEANT TO PASS]")
    void testTwoFactorSms_RecordNeverContainsTheCode() {
        given(notificationProviderService.dispatchSms(any(), any())).willReturn(true);
        Map<String, Object> event = Map.of(
                "action", "SMS_2FA_REQUESTED", "userId", "42", "phoneNumber", "+15551234567", "code", "987654");

        twoFactorSmsListener.consumeSmsRequest(event);

        // The real SMS keeps the code - redacting that would defeat the point of sending it.
        verify(notificationProviderService).dispatchSms(eq("+15551234567"), contains("987654"));

        ArgumentCaptor<NotificationRecord> saved = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRecordRepository).save(saved.capture());

        assertThat(saved.getValue().getMessage())
                .as("a one-time code must never be persisted to the notification feed")
                .doesNotContain("987654");
        // Masked to the last four digits, so the row still says which number was texted.
        assertThat(saved.getValue().getMessage()).isEqualTo("Verification code sent to ***4567.");
    }

    @Test
    @DisplayName("2FA SMS dispatch failure persists a FAILED notification record - [MEANT TO FAIL]")
    void testTwoFactorSms_Failure_PersistsFailedRecord() {
        // dispatchSms's @Recover swallows the underlying exception - the false return is the only
        // signal a failure happened, exactly what the listener now checks.
        given(notificationProviderService.dispatchSms(any(), any())).willReturn(false);
        Map<String, Object> event = Map.of(
                "action", "SMS_2FA_REQUESTED", "userId", "42", "phoneNumber", "+15551234567", "code", "123456");

        twoFactorSmsListener.consumeSmsRequest(event);

        verify(notificationRecordRepository).save(argThat(record -> record.getStatus() == NotificationStatus.FAILED));
    }

    @Test
    @DisplayName("Transaction alert above threshold persists a SENT notification record - [MEANT TO PASS]")
    void testTransactionAlert_AboveThreshold_PersistsRecord() {
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(42L, new BigDecimal("100.00"), true, 8, "UTC", "alerts@example.com"));
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);
        FundsTransferredEvent event = new FundsTransferredEvent(42L, 1L, 2L, new BigDecimal("500.00"), UUID.randomUUID());

        transactionAlertListener.consumeTransferEvent(event);

        verify(notificationRecordRepository).save(argThat(record ->
                record.getUserId().equals(42L)
                        && record.getType() == NotificationType.TRANSACTION_ALERT
                        && record.getChannel() == NotificationChannel.EMAIL
                        && record.getStatus() == NotificationStatus.SENT
        ));
    }

    @Test
    @DisplayName("Transaction alert below threshold does not persist a notification record - [MEANT TO FAIL]")
    void testTransactionAlert_BelowThreshold_DoesNotPersist() {
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(42L, new BigDecimal("1000.00"), true, 8, "UTC", "alerts@example.com"));
        FundsTransferredEvent event = new FundsTransferredEvent(42L, 1L, 2L, new BigDecimal("50.00"), UUID.randomUUID());

        transactionAlertListener.consumeTransferEvent(event);

        verify(notificationRecordRepository, never()).save(any());
    }

    // Users who registered before the email field existed have no address on file. Dispatching to the
    // old fabricated "user_<id>@bank.com" would have looked like a success while going nowhere, so the
    // listener now records the miss as FAILED and sends nothing.
    @Test
    @DisplayName("Transaction alert for a user with no email records FAILED and dispatches nothing - [MEANT TO PASS]")
    void testTransactionAlert_NoEmailOnFile_RecordsFailedWithoutDispatching() {
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(42L, new BigDecimal("100.00"), true, 8, "UTC", null));

        transactionAlertListener.consumeTransferEvent(new FundsTransferredEvent(
                42L, 1L, 2L, new BigDecimal("500.00"), UUID.randomUUID()));

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
        verify(notificationRecordRepository).save(argThat(record ->
                record.getUserId().equals(42L) && record.getStatus() == NotificationStatus.FAILED
        ));
    }

    @Test
    @DisplayName("Profile security update dispatches an email and persists a SENT record - [MEANT TO PASS]")
    void testProfileSecurity_PersistsRecord() {
        // Closes a pre-existing gap: this listener used to only log a line, never actually calling
        // NotificationProviderService despite its own comment claiming to.
        // Unlike the transaction alert above, this listener has no preferences object handed to it, so
        // it fetches one purely to resolve where the notice should be delivered.
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(42L, new BigDecimal("100.00"), true, 8, "UTC", "alerts@example.com"));
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);
        Map<String, Object> event = Map.of("userId", "42", "eventType", "CONTACT_INFO_UPDATED");

        profileNotificationListener.consumeProfileUpdate(event);

        verify(notificationProviderService).dispatchEmail(eq("alerts@example.com"), any(), any());
        verify(notificationRecordRepository).save(argThat(record ->
                record.getUserId().equals(42L)
                        && record.getType() == NotificationType.PROFILE_SECURITY
                        && record.getStatus() == NotificationStatus.SENT
        ));
    }

    @Test
    @DisplayName("GET /api/v1/notifications returns the caller's notifications resolved from their JWT userId - [MEANT TO PASS]")
    void testGetNotifications_ReturnsCallersRecords() throws Exception {
        given(notificationRecordRepository.findByUserId(eq(42L), any()))
                .willReturn(new PageImpl<>(List.of(buildRecord(42L))));

        mockMvc.perform(get("/api/v1/notifications").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("SMS_2FA"));
    }

    @Test
    @DisplayName("GET /api/v1/notifications is rejected for an unauthenticated caller - [MEANT TO FAIL]")
    void testGetNotifications_UnauthenticatedDenied() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().is4xxClientError());
    }

    // ==========================================
    // What the stored profile notice actually says
    // ==========================================

    // The whole point of reading the "changes" map profile-service has always published: the notice
    // used to read "an update of type 'CONTACT_INFO_CHANGE' was made to your profile", which names
    // neither the field that moved nor what it moved from.
    @Test
    @DisplayName("Profile notice names only the fields that actually changed, before and after - [MEANT TO PASS]")
    void testProfileNotice_NamesOnlyChangedFields() {
        givenProfileEmailOnFile();

        profileNotificationListener.consumeProfileUpdate(contactInfoEvent(
                oldState("+15712856947", "123 Main St", "Fairfax"),
                newState("+1 (571) 285-1234", "123 Main St", "Reston")));

        String message = capturedMessage();
        assertThat(message).isEqualTo(
                "Your profile was updated: phone number changed from ***6947 to ***1234; "
                        + "city changed from Fairfax to Reston.");
        // addressLine1 was resubmitted unchanged, so it must not appear at all - a form that echoes
        // back every field it was given is the noise this message replaces.
        assertThat(message).doesNotContain("address line 1");
    }

    // A user who opens the form, changes nothing and presses save still produces an event. Listing
    // all eight fields as "changed" there would train them to ignore the notice entirely.
    @Test
    @DisplayName("Profile notice claims no change when the form was resubmitted unedited - [MEANT TO PASS]")
    void testProfileNotice_UnchangedResubmissionClaimsNothing() {
        givenProfileEmailOnFile();

        profileNotificationListener.consumeProfileUpdate(contactInfoEvent(
                oldState("+15712856947", "123 Main St", "Fairfax"),
                newState("+15712856947", "123 Main St", "Fairfax")));

        String message = capturedMessage();
        assertThat(message).doesNotContain("changed from");
        assertThat(message).doesNotContain("set to");
    }

    // A field being filled in for the first time is a different sentence from a field being edited.
    // "city changed from null to Reston" is what the naive version of this reads like.
    @Test
    @DisplayName("Profile notice reads naturally for a field that was previously empty - [MEANT TO PASS]")
    void testProfileNotice_PreviouslyEmptyFieldReadsAsSet() {
        givenProfileEmailOnFile();

        profileNotificationListener.consumeProfileUpdate(contactInfoEvent(
                oldState("+15712856947", "123 Main St", null),
                newState("+15712856947", "123 Main St", "Reston")));

        String message = capturedMessage();
        assertThat(message).contains("city set to Reston");
        assertThat(message).doesNotContain("null");
    }

    // The privacy half of this message. The record is served back by GET /api/v1/notifications every
    // time the user reopens the page, which is exactly why V3 scrubbed 2FA codes out of stored rows -
    // a phone number written in full would sit in the feed indefinitely.
    @Test
    @DisplayName("Profile notice masks the phone number and stores no full number anywhere - [MEANT TO PASS]")
    void testProfileNotice_PhoneNumberIsMaskedInTheRecord() {
        givenProfileEmailOnFile();

        profileNotificationListener.consumeProfileUpdate(contactInfoEvent(
                oldState("+15712856947", "123 Main St", "Fairfax"),
                newState("571-285-1234", "123 Main St", "Fairfax")));

        String message = capturedMessage();
        assertThat(message).contains("phone number changed from ***6947 to ***1234");
        assertThat(message)
                .as("neither the old nor the new number may be recoverable from a stored record")
                .doesNotContain("+15712856947")
                .doesNotContain("5712856947")
                .doesNotContain("571-285-1234")
                .doesNotContain("2851234");
    }

    // This runs on a Kafka listener, so an exception thrown while describing the change would be
    // caught upstream and the notification would simply never be written. A vague notice beats none.
    @Test
    @DisplayName("Profile notice falls back to the generic wording when the changes map is malformed - [MEANT TO PASS]")
    void testProfileNotice_MalformedChangesFallsBackWithoutThrowing() {
        givenProfileEmailOnFile();

        Map<String, Object> garbage = new HashMap<>();
        garbage.put("userId", "42");
        garbage.put("eventType", "CONTACT_INFO_CHANGE");
        garbage.put("changes", "this is not a map at all");

        assertThatCode(() -> profileNotificationListener.consumeProfileUpdate(garbage))
                .doesNotThrowAnyException();

        assertThat(capturedMessage())
                .isEqualTo("Dear customer, an update of type 'CONTACT_INFO_CHANGE' was made to your profile.");
    }

    @Test
    @DisplayName("Profile notice falls back to the generic wording when no changes map is present - [MEANT TO PASS]")
    void testProfileNotice_MissingChangesFallsBack() {
        givenProfileEmailOnFile();

        // The shape a legacy publisher (or a different profile event type) sends: no changes at all.
        profileNotificationListener.consumeProfileUpdate(
                Map.of("userId", "42", "eventType", "CONTACT_INFO_CHANGE"));

        assertThat(capturedMessage())
                .isEqualTo("Dear customer, an update of type 'CONTACT_INFO_CHANGE' was made to your profile.");
    }

    // ==========================================
    // What the stored transaction alert actually says
    // ==========================================

    @Test
    @DisplayName("Transaction alert names the amount, both masked accounts and the counterparty - [MEANT TO PASS]")
    void testTransactionAlert_NamesAmountAccountsAndCounterparty() {
        givenAlertThresholdOf("100.00");
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);
        given(accountServiceClient.getAccountOwner(770015570L))
                .willReturn(new AccountServiceClient.AccountOwnerResponse(77L));
        given(authServiceClient.getDisplayName(77L))
                .willReturn(new AuthServiceClient.DisplayNameResponse(77L, "Mark"));

        transactionAlertListener.consumeTransferEvent(new FundsTransferredEvent(
                42L, 990013400L, 770015570L, new BigDecimal("1000.00"), UUID.randomUUID()));

        String message = capturedMessage();
        assertThat(message).contains("$1,000.00");
        assertThat(message).contains("........3400");
        assertThat(message).contains("........5570");
        assertThat(message).contains("Mark");
        // Same masking rule the rest of the project applies to account numbers - the stored row must
        // not carry a whole account reference for either side of the transfer.
        assertThat(message)
                .doesNotContain("990013400")
                .doesNotContain("770015570");
    }

    // The name is a nicety; the alert is not. transaction-service's resolveRecipientName degrades the
    // same way for the same reason.
    @Test
    @DisplayName("Transaction alert still sends with the masked account when the name lookup fails - [MEANT TO PASS]")
    void testTransactionAlert_FailedNameLookupStillSends() {
        givenAlertThresholdOf("100.00");
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);
        given(accountServiceClient.getAccountOwner(770015570L))
                .willReturn(new AccountServiceClient.AccountOwnerResponse(77L));
        given(authServiceClient.getDisplayName(77L)).willThrow(new RuntimeException("auth-service unavailable"));

        transactionAlertListener.consumeTransferEvent(new FundsTransferredEvent(
                42L, 990013400L, 770015570L, new BigDecimal("1000.00"), UUID.randomUUID()));

        verify(notificationProviderService).dispatchEmail(eq("alerts@example.com"), any(), any());
        verify(notificationRecordRepository).save(argThat(record ->
                record.getStatus() == NotificationStatus.SENT
                        && record.getMessage().contains("........5570")));
    }

    // An owner lookup that fails takes the display name with it - there is no user id left to ask
    // about - and the alert still has to go out.
    @Test
    @DisplayName("Transaction alert still sends when the owner lookup itself fails - [MEANT TO PASS]")
    void testTransactionAlert_FailedOwnerLookupStillSends() {
        givenAlertThresholdOf("100.00");
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);
        given(accountServiceClient.getAccountOwner(any())).willThrow(new RuntimeException("account-service unavailable"));

        transactionAlertListener.consumeTransferEvent(new FundsTransferredEvent(
                42L, 990013400L, 770015570L, new BigDecimal("1000.00"), UUID.randomUUID()));

        verify(notificationProviderService).dispatchEmail(eq("alerts@example.com"), any(), any());
        assertThat(capturedMessage()).contains("........5570");
        verify(authServiceClient, never()).getDisplayName(any());
    }

    // Moving money between your own checking and savings is not a payment to a stranger, and an alert
    // that describes it as one is the kind of thing that gets a support call.
    @Test
    @DisplayName("A transfer between the user's own accounts is worded as such - [MEANT TO PASS]")
    void testTransactionAlert_OwnAccountTransferIsWordedAsAMove() {
        givenAlertThresholdOf("100.00");
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);
        // Same user on both ends of the transfer.
        given(accountServiceClient.getAccountOwner(770015570L))
                .willReturn(new AccountServiceClient.AccountOwnerResponse(42L));

        transactionAlertListener.consumeTransferEvent(new FundsTransferredEvent(
                42L, 990013400L, 770015570L, new BigDecimal("1000.00"), UUID.randomUUID()));

        String message = capturedMessage();
        assertThat(message).contains("moved between your own accounts");
        assertThat(message).contains("........3400");
        assertThat(message).contains("........5570");
        // Their own name has no business appearing as a recipient, so the lookup is never made.
        verify(authServiceClient, never()).getDisplayName(any());
    }

    // ==========================================
    // Filtering on the feed
    // ==========================================

    @Test
    @DisplayName("GET /api/v1/notifications accepts every filter at once and still answers - [MEANT TO PASS]")
    void testGetNotifications_AllFiltersCombined() throws Exception {
        given(notificationRecordRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(buildRecord(42L))));

        mockMvc.perform(get("/api/v1/notifications")
                        .param("type", "SMS_2FA")
                        .param("channel", "SMS")
                        .param("status", "SENT")
                        .param("from", "2020-01-01T00:00:00")
                        .param("to", "2030-01-01T00:00:00")
                        .with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("SMS_2FA"));

        // A filtered request goes through the Specification, never through the unfiltered lookup.
        verify(notificationRecordRepository, never()).findByUserId(any(), any());
    }

    // The response is still a Spring Page of the same DTO, filters or not - the existing page reads
    // content/totalElements and must keep working without being taught anything new.
    @Test
    @DisplayName("A filtered response keeps the unfiltered response shape - [MEANT TO PASS]")
    void testGetNotifications_FilteredResponseShapeIsUnchanged() throws Exception {
        given(notificationRecordRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(buildRecord(42L))));

        mockMvc.perform(get("/api/v1/notifications").param("status", "SENT").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].channel").value("SMS"))
                .andExpect(jsonPath("$.content[0].status").value("SENT"))
                // createdAt is deliberately not asserted here: buildRecord is never persisted, so
                // @PrePersist never runs and the field is legitimately null on this fixture. The real
                // ordering-by-createdAt behaviour is proven against the database in
                // NotificationFeedFilterTestSuite instead.
                .andExpect(jsonPath("$.content[0].message").exists());
    }

    @Test
    @DisplayName("An unparseable enum filter is a 400 naming the accepted values - [MEANT TO PASS]")
    void testGetNotifications_BadEnumFilterIsReadable400() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").param("type", "NOT_A_TYPE").with(fullAuthUser(42)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("NOT_A_TYPE")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("TRANSACTION_ALERT")));
    }

    @Test
    @DisplayName("An unparseable date filter is a 400, not a 500 - [MEANT TO PASS]")
    void testGetNotifications_BadDateFilterIsReadable400() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").param("from", "last tuesday").with(fullAuthUser(42)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ISO date-time")));
    }

    // ==========================================
    // Helpers
    // ==========================================

    private void givenProfileEmailOnFile() {
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(
                        42L, new BigDecimal("100.00"), true, 8, "UTC", "alerts@example.com"));
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);
    }

    private void givenAlertThresholdOf(String threshold) {
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(
                        42L, new BigDecimal(threshold), true, 8, "UTC", "alerts@example.com"));
    }

    // The exact envelope ProfileManagementService.publishContactInfoChangedEvent puts on the
    // profile-events topic: userId, eventType, and a changes map holding the before and after states.
    private Map<String, Object> contactInfoEvent(Map<String, Object> oldState, Map<String, Object> newState) {
        Map<String, Object> changes = new HashMap<>();
        changes.put("old", oldState);
        changes.put("new", newState);

        Map<String, Object> event = new HashMap<>();
        event.put("userId", "42");
        event.put("eventType", "CONTACT_INFO_CHANGE");
        event.put("changes", changes);
        return event;
    }

    // profile-service's captureOldContactState snapshots these three fields and no others, so the
    // before-state genuinely has holes in it - a HashMap rather than Map.of because a null value here
    // is the real shape of a field the user had never filled in.
    private Map<String, Object> oldState(String phoneNumber, String addressLine1, String city) {
        Map<String, Object> state = new HashMap<>();
        state.put("phoneNumber", phoneNumber);
        state.put("addressLine1", addressLine1);
        state.put("city", city);
        return state;
    }

    // The whole submitted form, which is what the "new" side carries (the serialized DTO).
    private Map<String, Object> newState(String phoneNumber, String addressLine1, String city) {
        Map<String, Object> state = new HashMap<>();
        state.put("legalName", "Jane Doe");
        state.put("dateOfBirth", "1990-01-01");
        state.put("phoneNumber", phoneNumber);
        state.put("addressLine1", addressLine1);
        state.put("addressLine2", null);
        state.put("city", city);
        state.put("state", "VA");
        state.put("zipCode", "20190");
        return state;
    }

    private String capturedMessage() {
        ArgumentCaptor<NotificationRecord> saved = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRecordRepository).save(saved.capture());
        return saved.getValue().getMessage();
    }

    private NotificationRecord buildRecord(Long userId) {
        NotificationRecord record = new NotificationRecord();
        record.setUserId(userId);
        record.setType(NotificationType.SMS_2FA);
        record.setChannel(NotificationChannel.SMS);
        record.setMessage("Verification code sent to ***4567.");
        record.setStatus(NotificationStatus.SENT);
        return record;
    }
}
