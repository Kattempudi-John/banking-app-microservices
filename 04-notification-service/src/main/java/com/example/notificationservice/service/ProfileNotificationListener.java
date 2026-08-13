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

@Service
public class ProfileNotificationListener {

    private static final Logger logger = LoggerFactory.getLogger(ProfileNotificationListener.class);

    // The human label for each field profile-service can report, in the order a person reads a
    // contact form. A LinkedHashMap rather than a switch so the ordering and the labels live in one
    // place - the message is a sentence, and "phone number changed ..., city changed ..." reading in
    // form order is the difference between a line that scans and a line that has to be parsed.
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

            sendSecurityAlertEmail(userId, eventType, eventPayload.get("changes"));

            logger.info("Notification Service: Security alert sent for User ID: {}", userId);

        } catch (Exception e) {
            logger.error("Failed to send security notification", e);
        }
    }

    private void sendSecurityAlertEmail(Long userId, String eventType, Object changes) {
        String subject = "Security Alert: Your profile was recently updated";
        String body = describeChanges(changes, eventType);

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

    // The message this whole class exists to produce. profile-service has always published a
    // "changes" map alongside the event type (ProfileManagementService.publishContactInfoChangedEvent
    // sends Map.of("old", oldState, "new", dto)) and this listener used to throw it away, so the
    // stored notice could only name the event type - "an update of type 'CONTACT_INFO_CHANGE' was
    // made to your profile" tells the reader nothing about what actually moved.
    //
    // Every failure mode here falls back to that old generic wording rather than throwing. This runs
    // on a Kafka listener: an exception escaping into consumeProfileUpdate's catch block would lose
    // the notification entirely, and a vague notice is strictly better than no notice at all.
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

            // Absent from the before-state is NOT the same as "was empty". profile-service's
            // captureOldContactState only snapshots some of the form's fields, so for the rest we
            // know the submitted value and nothing to compare it against. Announcing those as
            // "legal name set to ..." would claim a change on every resubmission of an unedited
            // form, which is exactly the noise this message is meant to replace - so a field with
            // no before-value recorded is passed over in silence.
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
        // The phone number is masked on BOTH sides. This string is persisted and served back by
        // GET /api/v1/notifications every time the user reopens the page, which is the same reason
        // V3 scrubbed 2FA codes out of stored records - the row should say which field moved, not
        // republish the contact details themselves. The last four digits are enough for the reader
        // to recognise their own number, and the other fields (name, address, city) are not masked
        // because they are the user's own data being shown back to them, not a credential.
        boolean maskValue = "phoneNumber".equals(key);
        String from = maskValue ? maskPhoneNumber(oldValue) : oldValue;
        String to = maskValue ? maskPhoneNumber(newValue) : newValue;

        if (isBlank(oldValue)) {
            // "changed from null to Reston" is what the naive version of this reads like for a field
            // the user is filling in for the first time.
            return label + " set to " + to;
        }
        return label + " changed from " + from + " to " + to;
    }

    // The old and new sides arrive as JSON objects, so Jackson hands them over as maps whatever they
    // were on the publishing side (a HashMap for "old", a serialized DTO for "new"). Anything else -
    // a missing key, a null, a string where an object was expected - is a malformed payload and
    // answers null so the caller can fall back.
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

    // Null and "" are the same thing to a reader, and profile-service stores optional fields (address
    // line 2 in particular) as either depending on how the form was submitted - comparing them
    // literally would report a change nobody made.
    private boolean unchanged(String oldValue, String newValue) {
        if (isBlank(oldValue) && isBlank(newValue)) {
            return true;
        }
        return oldValue != null && oldValue.equals(newValue);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // Same "***4567" shape TwoFactorSmsListener writes into its own records, so a user reading their
    // feed sees one masking style rather than two.
    private String maskPhoneNumber(String phoneNumber) {
        if (isBlank(phoneNumber)) {
            return "the number on file";
        }
        String digitsOnly = phoneNumber.replaceAll("\\D", "");
        String source = digitsOnly.isEmpty() ? phoneNumber : digitsOnly;
        if (source.length() <= 4) {
            // Too short to mask meaningfully, and printing it whole would defeat the point.
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
