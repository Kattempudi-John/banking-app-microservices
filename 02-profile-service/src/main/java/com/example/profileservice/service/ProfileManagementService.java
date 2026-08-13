package com.example.profileservice.service;

import com.example.profileservice.client.AuthServiceClient;
import com.example.profileservice.dto.ContactInfoResponseDto;
import com.example.profileservice.dto.UpdateContactInfoRequestDto;
import com.example.profileservice.model.KycOverrideAuditLog;
import com.example.profileservice.model.KycStatus;
import com.example.profileservice.model.UserProfile;
import com.example.profileservice.repository.KycOverrideAuditLogRepository;
import com.example.profileservice.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(ProfileManagementService.class);

    private final UserProfileRepository userProfileRepository;
    private final KycOverrideAuditLogRepository auditLogRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AuthServiceClient authServiceClient;

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
                                    AuthServiceClient authServiceClient) {
        this.userProfileRepository = userProfileRepository;
        this.auditLogRepository = auditLogRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.authServiceClient = authServiceClient;
    }

    // @Transactional here matters more than it looks, if publishing to kafka down in step 4
    // ever threw, the db save from step 3 would get rolled back too since both are in one transaction
    @Transactional
    public void updateContactInfo(Long userId, UpdateContactInfoRequestDto dto) {
        UserProfile user = userProfileRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 1. Capture the before state for the audit trail
        Map<String, String> oldState = captureOldContactState(user);

        // 2. Local rules first, so a submission that was never going to be accepted doesn't leave a
        // changed phone number behind in auth-service - that write is a real side effect on the
        // number 2FA codes get sent to, not something to do speculatively.
        requireApplicantIsAdult(dto.getDateOfBirth());

        // 3. Hand the phone number to auth-service, which owns it, BEFORE anything is written here
        // and before any KYC approval. It answers with the number in E.164; that answer is what gets
        // stored, rather than normalizing a second time locally - two normalizers agreeing today is
        // not a guarantee they agree forever, and the whole point of this call is that there is one
        // source of truth for this field. If it rejects the number, this throws and nothing below
        // runs: no save, no Kafka event, and critically no approval.
        String normalizedPhone = registerPhoneNumberWithAuthService(userId, dto.getPhoneNumber());

        // 4. Apply new state
        user.setLegalName(dto.getLegalName());
        user.setDateOfBirth(dto.getDateOfBirth());
        user.setPhoneNumber(normalizedPhone);
        user.setAddressLine1(dto.getAddressLine1());
        user.setAddressLine2(dto.getAddressLine2());
        user.setCity(dto.getCity());
        user.setState(dto.getState());
        user.setZipCode(dto.getZipCode());

        // 5. Save to database
        userProfileRepository.save(user);

        // 6. Publish to Kafka with userId as the routing key
        publishContactInfoChangedEvent(userId, oldState, dto);

        // 7. Submitting a complete identity IS the verification here - the user has given a legal
        // name, date of birth and address, and every field was validated before reaching this point.
        autoApproveKycIfEligible(userId, user);
    }

    // What the identity form pre-fills from. The phone number comes from auth-service because that is
    // the copy that matters - showing this service's mirror instead would put the old number back in
    // front of a user who just changed it, which is exactly the confusion this whole change removes.
    // Falls back to the local copy only when auth-service can't be reached, so an outage degrades to
    // a possibly-stale pre-fill rather than an empty form (and an empty form is what gets retyped
    // wrong).
    @Transactional(readOnly = true)
    public ContactInfoResponseDto getContactInfo(Long userId) {
        // No provisioning on read here, unlike the KYC status lookup: a user with no profile row has
        // simply never filled this form in, and an all-null response renders as the blank form that
        // is the correct thing to show them.
        UserProfile user = userProfileRepository.findById(userId).orElse(null);

        return new ContactInfoResponseDto(
                user == null ? null : user.getLegalName(),
                user == null ? null : user.getDateOfBirth(),
                resolveOwnedPhoneNumber(userId, user),
                user == null ? null : user.getAddressLine1(),
                user == null ? null : user.getAddressLine2(),
                user == null ? null : user.getCity(),
                user == null ? null : user.getState(),
                user == null ? null : user.getZipCode());
    }

    private String resolveOwnedPhoneNumber(Long userId, UserProfile localProfile) {
        try {
            AuthServiceClient.PhoneNumberResponse response = authServiceClient.getPhoneNumber(userId);
            if (response != null) {
                return response.phoneNumber();
            }
        } catch (RuntimeException e) {
            // Deliberately swallowed: this is a read used to pre-fill a form. Failing the whole
            // request would leave the user unable to see their own details because a different
            // service is down. The write path below does the opposite, and must.
            logger.warn("Could not read the phone number for user id {} from auth-service; falling back "
                    + "to this service's local copy, which may be stale.", userId, e);
        }
        return localProfile == null ? null : localProfile.getPhoneNumber();
    }

    // Returns the E.164 number auth-service accepted and stored. Every failure path here aborts the
    // caller's transaction - there is no "carry on without it" option, because saving a number
    // auth-service never agreed to is how the two services started disagreeing about which number
    // receives a user's login codes.
    private String registerPhoneNumberWithAuthService(Long userId, String rawPhoneNumber) {
        AuthServiceClient.PhoneNumberResponse response;
        try {
            response = authServiceClient.updatePhoneNumber(userId,
                    new AuthServiceClient.UpdatePhoneNumberRequest(rawPhoneNumber));
        } catch (ResponseStatusException e) {
            throw translateAuthServiceFailure(e);
        } catch (RuntimeException e) {
            // Connection refused, timeout, DNS - never reached auth-service at all. Feign raises
            // these before the ErrorDecoder is ever consulted, so they arrive as something other
            // than a ResponseStatusException.
            throw phoneNumberUnverifiable(userId, e);
        }

        if (response == null || response.phoneNumber() == null || response.phoneNumber().isBlank()) {
            // A 200 with nothing usable in it means the contract was not honoured. Treated the same
            // as unreachable rather than guessed at, since the alternative is storing a number that
            // may not be the one auth-service actually holds.
            throw phoneNumberUnverifiable(userId, null);
        }

        return response.phoneNumber();
    }

    private ResponseStatusException translateAuthServiceFailure(ResponseStatusException e) {
        if (e.getStatusCode() == HttpStatus.CONFLICT) {
            // The bug this whole change exists for: the signup form already answers 409 for a number
            // someone else holds, and this form used to be a way straight around that rule.
            return new ResponseStatusException(HttpStatus.CONFLICT,
                    "That phone number is already registered to another account");
        }
        if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
            // auth-service's own wording, passed through unchanged so the user is told the same
            // thing here as they would be told at signup.
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getReason());
        }
        return phoneNumberUnverifiable(null, e);
    }

    private ResponseStatusException phoneNumberUnverifiable(Long userId, Exception cause) {
        logger.error("Could not register the phone number for user id {} with auth-service. The profile "
                + "update was rejected rather than saved, so nothing here was approved on an unverified "
                + "number.", userId, cause);
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "We couldn't verify your phone number just now. Please try again in a moment.");
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

    // Every field the identity form can change is snapshotted, because this map is the ONLY record
    // of the before-value: notification-service diffs it against the submitted DTO to tell the user
    // what actually changed, and audit-service stores it. While this captured only three fields, a
    // legal-name or state change was invisible on both sides - the alert could say nothing about it
    // and the audit trail had nothing to compare against.
    //
    // Keys must match the JSON property names on UpdateContactInfoRequestDto, since the consumer
    // pairs the two by name. HashMap rather than Map.of on purpose: these values are routinely null
    // for a profile that has never been completed, and Map.of rejects nulls outright.
    private Map<String, String> captureOldContactState(UserProfile user) {
        Map<String, String> oldState = new HashMap<>();
        oldState.put("legalName", user.getLegalName());
        // ISO yyyy-MM-dd, the same shape @JsonFormat gives the incoming DTO - formatting the two
        // sides differently would read as a change on every submission that never touched the date.
        oldState.put("dateOfBirth", user.getDateOfBirth() != null ? user.getDateOfBirth().toString() : null);
        oldState.put("phoneNumber", user.getPhoneNumber());
        oldState.put("addressLine1", user.getAddressLine1());
        oldState.put("addressLine2", user.getAddressLine2());
        oldState.put("city", user.getCity());
        oldState.put("state", user.getState());
        oldState.put("zipCode", user.getZipCode());
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