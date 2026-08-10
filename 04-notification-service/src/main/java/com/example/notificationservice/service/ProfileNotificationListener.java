package com.example.notificationservice.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

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

    public ProfileNotificationListener(NotificationProviderService notificationProviderService,
                                        NotificationRecordRepository notificationRecordRepository) {
        this.notificationProviderService = notificationProviderService;
        this.notificationRecordRepository = notificationRecordRepository;
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

        // Same generated placeholder address TransactionAlertListener uses - no real user email
        // lookup exists anywhere in this system yet (see README's known limitations).
        String userEmail = "user_" + userId + "@bank.com";
        boolean dispatched = notificationProviderService.dispatchEmail(userEmail, subject, body);

        persistRecord(userId, subject, body, dispatched ? NotificationStatus.SENT : NotificationStatus.FAILED);
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