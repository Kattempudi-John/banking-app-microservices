package com.example.notificationservice;

import com.example.notificationservice.client.ProfileServiceClient;
import com.example.notificationservice.event.FundsTransferredEvent;
import com.example.notificationservice.model.NotificationChannel;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(42L, new BigDecimal("100.00"), true, "UTC", "alerts@example.com"));
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
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(42L, new BigDecimal("1000.00"), true, "UTC", "alerts@example.com"));
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
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(42L, new BigDecimal("100.00"), true, "UTC", null));

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
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(42L, new BigDecimal("100.00"), true, "UTC", "alerts@example.com"));
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

    private NotificationRecord buildRecord(Long userId) {
        NotificationRecord record = new NotificationRecord();
        record.setUserId(userId);
        record.setType(NotificationType.SMS_2FA);
        record.setChannel(NotificationChannel.SMS);
        record.setMessage("Your verification code is 123456.");
        record.setStatus(NotificationStatus.SENT);
        return record;
    }
}
