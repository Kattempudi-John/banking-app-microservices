package com.example.notificationservice.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.notificationservice.client.ProfileServiceClient;
import com.example.notificationservice.model.NotificationChannel;
import com.example.notificationservice.model.NotificationRecord;
import com.example.notificationservice.model.NotificationStatus;
import com.example.notificationservice.model.NotificationType;
import com.example.notificationservice.repository.NotificationRecordRepository;

@Service
public class ProfileNotificationListener {

    private static final Logger logger = LoggerFactory.getLogger(ProfileNotificationListener.class);

    private final NotificationProviderService notificationProviderService;
    private final NotificationRecordRepository notificationRecordRepository;
    private final ProfileServiceClient profileServiceClient;

    public ProfileNotificationListener(NotificationProviderService notificationProviderService,
                                        NotificationRecordRepository notificationRecordRepository,
                                        ProfileServiceClient profileServiceClient) {
        this.notificationProviderService = notificationProviderService;
        this.notificationRecordRepository = notificationRecordRepository;
        this.profileServiceClient = profileServiceClient;
    }

    // learned groupId matters a lot here, every service listening with the same group id shares
    // the messages between them like a queue, a different group id (like audit-service uses)
    // gets its own full independent copy of every message instead, that is how both services
    // can react to the exact same profile-events topic without stepping on each other
    @KafkaListener(topics = "profile-events", groupId = "notification-service-group")
    public void consumeProfileUpdate(Map<String, Object> eventPayload) {
        try {
            logger.info("Notification Service: Processing profile update for security alert.");

            Long userId = Long.valueOf(eventPayload.get("userId").toString());
            String eventType = (String) eventPayload.get("eventType");

            sendSecurityAlertEmail(userId, eventType);

            logger.info("Notification Service: Security alert sent for User ID: {}", userId);

        } catch (Exception e) {
            logger.error("Failed to send security notification", e);
        }
    }

    private void sendSecurityAlertEmail(Long userId, String eventType) {
        String subject = "Security Alert: Your profile was recently updated";
        String body = String.format("Dear customer, an update of type '%s' was made to your profile.", eventType);

        // Unlike TransactionAlertListener, this listener has no preferences object already in hand,
        // so it fetches one purely for the address - the call is @Cacheable, so repeated profile
        // updates for the same user don't each cost a round trip.
        String userEmail = resolveEmail(userId);
        if (userEmail == null || userEmail.isBlank()) {
            logger.warn("User {} has no email address on file - skipping the profile security alert", userId);
            persistRecord(userId, subject, body, NotificationStatus.FAILED);
            return;
        }

        boolean dispatched = notificationProviderService.dispatchEmail(userEmail, subject, body);

        persistRecord(userId, subject, body, dispatched ? NotificationStatus.SENT : NotificationStatus.FAILED);
    }

    private String resolveEmail(Long userId) {
        try {
            ProfileServiceClient.UserPreferenceResponse preferences = profileServiceClient.getUserPreferences(userId);
            return preferences != null ? preferences.email() : null;
        } catch (RuntimeException e) {
            logger.error("Could not resolve an email address for user {}", userId, e);
            return null;
        }
    }

    private void persistRecord(Long userId, String subject, String message, NotificationStatus status) {
        NotificationRecord record = new NotificationRecord();
        record.setUserId(userId);
        record.setType(NotificationType.PROFILE_SECURITY);
        record.setChannel(NotificationChannel.EMAIL);
        record.setSubject(subject);
        record.setMessage(message);
        record.setStatus(status);
        notificationRecordRepository.save(record);
    }
}