package com.example.profileservice.service;

import com.example.profileservice.dto.UpdateContactInfoRequestDto;
import com.example.profileservice.model.KycOverrideAuditLog;
import com.example.profileservice.model.KycStatus;
import com.example.profileservice.model.UserProfile;
import com.example.profileservice.repository.KycOverrideAuditLogRepository;
import com.example.profileservice.repository.UserProfileRepository;
import com.example.profileservice.util.PhoneNumberNormalizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.HashMap;
import java.util.Map;

@Service
public class ProfileManagementService {

    private final UserProfileRepository userProfileRepository;
    private final KycOverrideAuditLogRepository auditLogRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final PhoneNumberNormalizer phoneNumberNormalizer;

    // Kafka Topic Constants
    private static final String PROFILE_EVENTS_TOPIC = "profile-events";
    private static final String KYC_EVENTS_TOPIC = "kyc-events";

    private static final int MINIMUM_AGE_YEARS = 18;

    // The same flag that used to gate the Simulate-KYC-Approval button (true in application.yml,
    // false in application-prod.yml). Reused deliberately: this is the same affordance, just moved
    // from a fake button onto the real verification form.
    @Value("${app.demo.enabled:false}")
    private boolean demoAutoApprovalEnabled;

    public ProfileManagementService(UserProfileRepository userProfileRepository,
                                    KycOverrideAuditLogRepository auditLogRepository,
                                    KafkaTemplate<String, Object> kafkaTemplate,
                                    PhoneNumberNormalizer phoneNumberNormalizer) {
        this.userProfileRepository = userProfileRepository;
        this.auditLogRepository = auditLogRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.phoneNumberNormalizer = phoneNumberNormalizer;
    }

    // @Transactional here matters more than it looks, if publishing to kafka down in step 4
    // ever threw, the db save from step 3 would get rolled back too since both are in one transaction
    @Transactional
    public void updateContactInfo(Long userId, UpdateContactInfoRequestDto dto) {
        UserProfile user = userProfileRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 1. Capture the before state for the audit trail
        Map<String, String> oldState = captureOldContactState(user);

        // 2. Apply new state. The phone number is stored in E.164 regardless of how it was typed, so
        // this copy stays in the same shape as the one auth-service sends 2FA codes to.
        String normalizedPhone = phoneNumberNormalizer.normalize(dto.getPhoneNumber())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Please enter a valid phone number, e.g. 571-285-6947 or +15712856947"));

        requireApplicantIsAdult(dto.getDateOfBirth());

        user.setLegalName(dto.getLegalName());
        user.setDateOfBirth(dto.getDateOfBirth());
        user.setPhoneNumber(normalizedPhone);
        user.setAddressLine1(dto.getAddressLine1());
        user.setAddressLine2(dto.getAddressLine2());
        user.setCity(dto.getCity());
        user.setState(dto.getState());
        user.setZipCode(dto.getZipCode());

        // 3. Save to database
        userProfileRepository.save(user);

        // 4. Publish to Kafka with userId as the routing key
        publishContactInfoChangedEvent(userId, oldState, dto);

        // 5. Submitting a complete identity IS the verification here - the user has given a legal
        // name, date of birth and address, and every field was validated before reaching this point.
        autoApproveKycIfEligible(userId, user);
    }

    // The DTO's @Past only rejects future dates; the minimum-age rule needs real date arithmetic, so
    // it lives here. Rejecting rather than silently leaving the profile PENDING_VERIFICATION because
    // a user who is told nothing would just keep resubmitting the same form wondering why transfers
    // are still blocked.
    private void requireApplicantIsAdult(LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            return; // @NotNull on the DTO already covers this; nothing useful to add here.
        }
        if (Period.between(dateOfBirth, LocalDate.now()).getYears() < MINIMUM_AGE_YEARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You must be at least " + MINIMUM_AGE_YEARS + " years old to open an account.");
        }
    }

    // Stands in for the identity vendor's callback, which is the only thing that would approve a user
    // in a real deployment (see ProfileController.handleKycWebhook). Gated on app.demo.enabled for
    // exactly that reason: locally this makes the app usable end to end without a vendor account,
    // while a real deployment still has to hear from the vendor before any money can move.
    //
    // Only ever promotes from PENDING_VERIFICATION. A REJECTED user editing their address must not
    // be able to clear their own rejection - that decision belongs to the vendor or a compliance
    // officer's override, not to the applicant.
    private void autoApproveKycIfEligible(Long userId, UserProfile user) {
        if (!demoAutoApprovalEnabled) {
            return;
        }
        if (user.getKycStatus() != KycStatus.PENDING_VERIFICATION) {
            return;
        }

        // Routed through the same method the real webhook uses, so the status change, the idempotency
        // guard and the kyc-events broadcast all behave identically no matter what triggered it.
        // This is a self-invocation, so processKycWebhook's own @Transactional is bypassed by the
        // proxy - which is harmless and in fact wanted here, because updateContactInfo's transaction
        // is already open and the approval should commit or roll back together with the identity it
        // was granted on, never on its own.
        processKycWebhook(userId, KycStatus.APPROVED);
    }

    private Map<String, String> captureOldContactState(UserProfile user) {
        Map<String, String> oldState = new HashMap<>();
        oldState.put("phoneNumber", user.getPhoneNumber());
        oldState.put("addressLine1", user.getAddressLine1());
        oldState.put("city", user.getCity());
        // ... (capture other fields as needed)
        return oldState;
    }

    private void publishContactInfoChangedEvent(Long userId, Map<String, String> oldState, UpdateContactInfoRequestDto dto) {
        Map<String, Object> changes = Map.of("old", oldState, "new", dto);
        ProfileUpdatedEvent event = new ProfileUpdatedEvent(
                userId, LocalDateTime.now(), "CONTACT_INFO_CHANGE", changes
        );

        kafkaTemplate.send(PROFILE_EVENTS_TOPIC, String.valueOf(userId), event);
    }

    @Transactional
    public void processKycWebhook(Long userId, KycStatus newStatus) {
        UserProfile user = userProfileRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getKycStatus() == newStatus) {
            return; // Idempotent check: Prevent spamming Kafka if status hasn't actually changed
        }

        KycStatus oldStatus = user.getKycStatus();
        user.setKycStatus(newStatus);
        userProfileRepository.save(user);

        // Broadcast to Notification Service
        KycStatusUpdatedEvent event = new KycStatusUpdatedEvent(userId, oldStatus, newStatus, LocalDateTime.now());
        kafkaTemplate.send(KYC_EVENTS_TOPIC, String.valueOf(userId), event);
    }

    @Transactional
    public void adminOverrideKyc(Long userId, Long adminId, KycStatus newStatus, String reason) {
        UserProfile user = userProfileRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        KycStatus oldStatus = user.getKycStatus();

        // 1. Log the override in the immutable audit table
        recordOverrideAuditLog(userId, adminId, oldStatus, newStatus, reason);

        // 2. Update the user's status
        user.setKycStatus(newStatus);
        userProfileRepository.save(user);

        // 3. Publish the exact same Kafka event as the webhook
        KycStatusUpdatedEvent event = new KycStatusUpdatedEvent(userId, oldStatus, newStatus, LocalDateTime.now());
        kafkaTemplate.send(KYC_EVENTS_TOPIC, String.valueOf(userId), event);
    }

    private void recordOverrideAuditLog(Long userId, Long adminId, KycStatus oldStatus, KycStatus newStatus, String reason) {
        KycOverrideAuditLog auditLog = new KycOverrideAuditLog(
                userId, adminId, oldStatus, newStatus, reason
        );
        auditLogRepository.save(auditLog);
    }

    // =========================================================================================
    // DTO Records for Kafka Events (Typically placed in a shared library, defined here for clarity)
    // =========================================================================================
    // records are a newer java feature, this one line auto generates the constructor, getters,
    // equals, hashcode and toString, a lot shorter than writing all of that out by hand like the
    // model classes in the auth service do
    public record ProfileUpdatedEvent(Long userId, LocalDateTime timestamp, String eventType, Map<String, Object> changes) {}
    public record KycStatusUpdatedEvent(Long userId, KycStatus oldStatus, KycStatus newStatus, LocalDateTime timestamp) {}
}