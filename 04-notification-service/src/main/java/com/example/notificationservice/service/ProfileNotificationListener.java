package com.example.notificationservice.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

/**
 * Emails a user a security alert naming what changed whenever their profile is updated.
 *
 * <p>The stored message names each field that moved, in the order a person reads a contact form. The
 * phone number is masked on both sides of the change because this string is persisted and served
 * back by {@code GET /api/v1/notifications} on every reopen — the row should say which field moved,
 * not republish the contact details. Other fields are shown in full, being the user's own data rather
 * than a credential.
 */
@Service
public class ProfileNotificationListener {

    private static final Logger logger = LoggerFactory.getLogger(ProfileNotificationListener.class);

    private static final Map<String, String> FIELD_LABELS = buildFieldLabels();

    private static Map<String, String> buildFieldLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("legalName", "legal name");
        labels.put("dateOfBirth", "date of birth");
        labels.put("phoneNumber", "phone number");
        labels.put("addressLine1", "address line 1");
        labels.put("addressLine2", "address line 2");
        labels.put("city", "city");
        labels.put("state", "state");
        labels.put("zipCode", "zip code");
        return labels;
    }

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

    /**
     * Consumes a {@code profile-events} message and emails the affected user a security alert.
     *
     * <p>Never throws. Any failure is logged and the message is treated as consumed, because an
     * exception escaping a Kafka listener redelivers the same message indefinitely and stalls the
     * partition behind it. The cost is that a lost alert is lost, visible only in the log or as a
     * {@code FAILED} record.
     *
     * <p>The consumer group is shared with this service's other listeners, so each message is handled
     * once here. Services that need their own copy of the same topic — audit-service does — must use
     * a different group id rather than joining this one.
     *
     * @param eventPayload must carry a numeric {@code userId} and a {@code eventType}; the
     *     {@code changes} entry is optional and a missing or malformed one degrades the message to
     *     generic wording rather than failing
     */
    @KafkaListener(topics = "profile-events", groupId = "notification-service-group")
    public void consumeProfileUpdate(Map<String, Object> eventPayload) {
        try {
            logger.info("Notification Service: Processing profile update for security alert.");

            Long userId = Long.valueOf(eventPayload.get("userId").toString());
            String eventType = (String) eventPayload.get("eventType");

            sendSecurityAlertEmail(userId, eventType, eventPayload.get("changes"));

            logger.info("Notification Service: Security alert sent for User ID: {}", userId);

        } catch (Exception e) {
            logger.error("Failed to send security notification", e);
        }
    }

    private void sendSecurityAlertEmail(Long userId, String eventType, Object changes) {
        String subject = "Security Alert: Your profile was recently updated";
        String body = describeChanges(changes, eventType);

        String userEmail = resolveEmail(userId);
        if (userEmail == null || userEmail.isBlank()) {
            logger.warn("User {} has no email address on file - skipping the profile security alert", userId);
            persistRecord(userId, subject, body, NotificationStatus.FAILED);
            return;
        }

        boolean dispatched = notificationProviderService.dispatchEmail(userEmail, subject, body);

        persistRecord(userId, subject, body, dispatched ? NotificationStatus.SENT : NotificationStatus.FAILED);
    }

    private String describeChanges(Object changes, String eventType) {
        try {
            List<String> descriptions = collectFieldDescriptions(changes);
            if (descriptions.isEmpty()) {
                return genericMessage(eventType);
            }
            return "Your profile was updated: " + String.join("; ", descriptions) + ".";
        } catch (RuntimeException e) {
            logger.warn("Could not describe the profile changes for a '{}' event - falling back to the "
                    + "generic wording", eventType, e);
            return genericMessage(eventType);
        }
    }

    private List<String> collectFieldDescriptions(Object changes) {
        Map<String, Object> oldState = extractSide(changes, "old");
        Map<String, Object> newState = extractSide(changes, "new");
        if (oldState == null || newState == null) {
            return List.of();
        }

        List<String> descriptions = new ArrayList<>();
        for (Map.Entry<String, String> field : FIELD_LABELS.entrySet()) {
            String key = field.getKey();

            if (!oldState.containsKey(key)) {
                continue;
            }

            String oldValue = asText(oldState.get(key));
            String newValue = asText(newState.get(key));
            if (unchanged(oldValue, newValue)) {
                continue;
            }

            descriptions.add(describeField(field.getValue(), key, oldValue, newValue));
        }
        return descriptions;
    }

    private String describeField(String label, String key, String oldValue, String newValue) {
        boolean maskValue = "phoneNumber".equals(key);
        String from = maskValue ? maskPhoneNumber(oldValue) : oldValue;
        String to = maskValue ? maskPhoneNumber(newValue) : newValue;

        if (isBlank(oldValue)) {
            return label + " set to " + to;
        }
        return label + " changed from " + from + " to " + to;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSide(Object changes, String side) {
        if (!(changes instanceof Map<?, ?> changesMap)) {
            return null;
        }
        Object value = changesMap.get(side);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private String asText(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private boolean unchanged(String oldValue, String newValue) {
        if (isBlank(oldValue) && isBlank(newValue)) {
            return true;
        }
        return oldValue != null && oldValue.equals(newValue);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String maskPhoneNumber(String phoneNumber) {
        if (isBlank(phoneNumber)) {
            return "the number on file";
        }
        String digitsOnly = phoneNumber.replaceAll("\\D", "");
        String source = digitsOnly.isEmpty() ? phoneNumber : digitsOnly;
        if (source.length() <= 4) {
            return "***";
        }
        return "***" + source.substring(source.length() - 4);
    }

    private String genericMessage(String eventType) {
        return String.format("Dear customer, an update of type '%s' was made to your profile.", eventType);
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
