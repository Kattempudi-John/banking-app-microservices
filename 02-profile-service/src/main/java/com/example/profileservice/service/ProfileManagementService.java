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

/**
 * Owns a user's identity details and their KYC status.
 *
 * <p>Submitting a complete identity is what drives verification in this service: there is no
 * separate "verify me" action. Every path that can change a KYC status — the vendor webhook, a
 * compliance override, and the demo auto-approval — funnels through {@link #processKycWebhook} so
 * the status change, the idempotency guard, and the {@code kyc-events} broadcast behave identically
 * whatever triggered them. Downstream, transaction-service refuses to move money for a user who is
 * not {@code APPROVED}, so everything here is on the path to a customer being able to transact.
 *
 * <p>The phone number is not owned here. auth-service holds the copy that 2FA codes are sent to and
 * enforces uniqueness on it, so this service writes through to auth-service and stores back what it
 * answers with, rather than maintaining an independent second copy.
 */
@Service
public class ProfileManagementService {

    private static final Logger logger = LoggerFactory.getLogger(ProfileManagementService.class);

    private final UserProfileRepository userProfileRepository;
    private final KycOverrideAuditLogRepository auditLogRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AuthServiceClient authServiceClient;

    private static final String PROFILE_EVENTS_TOPIC = "profile-events";
    private static final String KYC_EVENTS_TOPIC = "kyc-events";

    private static final int MINIMUM_AGE_YEARS = 18;

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

    /**
     * Applies a submitted identity — legal name, date of birth, phone number, and address — and
     * verifies the applicant off the back of it.
     *
     * <p>The order of the steps is the contract, not an implementation detail:
     *
     * <ol>
     *   <li>the current values are snapshotted first, because they are the only record of the
     *       before-state that consumers get;
     *   <li>the minimum-age rule is checked next, so a submission that was never going to be
     *       accepted does not leave a changed phone number behind in auth-service — that write is a
     *       real side effect on where 2FA codes are delivered and must not be done speculatively;
     *   <li>the phone number is handed to auth-service, which owns it, <em>before</em> anything is
     *       written locally and before any approval. Its answer, already in E.164, is what gets
     *       stored — normalizing a second time here would risk two normalizers drifting apart, and
     *       the point of the call is that one service is the source of truth. A rejection throws and
     *       nothing after it runs: no save, no event, and critically no approval;
     *   <li>only then are the new values saved, the change published, and the applicant
     *       auto-approved.
     * </ol>
     *
     * <p>All of that is one transaction, so a failure anywhere — including the Kafka publish — rolls
     * back the local save and the approval together. The auth-service write is the exception: it has
     * already committed remotely and is not undone, which is why it is ordered after every local
     * check that could reject the submission.
     *
     * <p>Approval only happens where the demo flag permits it and only from
     * {@code PENDING_VERIFICATION}; see {@link #processKycWebhook} for what a caller can rely on
     * about the resulting status.
     *
     * @param userId never {@code null}; must already have a profile row, and is taken from the
     *     caller's JWT rather than the request body
     * @param dto fully validated by bean validation before arrival; the phone number must be one
     *     auth-service accepts and no other user holds, and the date of birth must put the applicant
     *     at 18 or over
     * @throws ResponseStatusException {@code 400} when the applicant is under 18 or auth-service
     *     cannot resolve the number, {@code 409} when the number is already registered to another
     *     account, {@code 503} when auth-service cannot be reached or answers without a usable
     *     number — in every case nothing is saved and no approval is granted
     * @throws RuntimeException when no profile row exists for {@code userId}
     */
    @Transactional
    public void updateContactInfo(Long userId, UpdateContactInfoRequestDto dto) {
        UserProfile user = userProfileRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Map<String, String> oldState = captureOldContactState(user);

        requireApplicantIsAdult(dto.getDateOfBirth());

        String normalizedPhone = registerPhoneNumberWithAuthService(userId, dto.getPhoneNumber());

        user.setLegalName(dto.getLegalName());
        user.setDateOfBirth(dto.getDateOfBirth());
        user.setPhoneNumber(normalizedPhone);
        user.setAddressLine1(dto.getAddressLine1());
        user.setAddressLine2(dto.getAddressLine2());
        user.setCity(dto.getCity());
        user.setState(dto.getState());
        user.setZipCode(dto.getZipCode());

        userProfileRepository.save(user);

        publishContactInfoChangedEvent(userId, oldState, dto);

        autoApproveKycIfEligible(userId, user);
    }

    /**
     * Returns the identity details the profile form pre-fills from.
     *
     * <p>The phone number comes from auth-service, the service that owns it; showing this service's
     * mirror instead would put the old number back in front of a user who has just changed it. When
     * auth-service cannot be reached the local copy is used instead, so an outage degrades to a
     * possibly-stale pre-fill rather than a blank form — a blank form is what gets retyped wrong, and
     * is the original cause of two accounts sharing one number.
     *
     * @param userId never {@code null}; a user with no profile row is not an error and no row is
     *     created for them, unlike the KYC status lookup
     * @return never {@code null}; every field is {@code null} for a user who has never completed the
     *     form, which the page renders as the blank form that is correct to show them
     */
    @Transactional(readOnly = true)
    public ContactInfoResponseDto getContactInfo(Long userId) {
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
            logger.warn("Could not read the phone number for user id {} from auth-service; falling back "
                    + "to this service's local copy, which may be stale.", userId, e);
        }
        return localProfile == null ? null : localProfile.getPhoneNumber();
    }

    private String registerPhoneNumberWithAuthService(Long userId, String rawPhoneNumber) {
        AuthServiceClient.PhoneNumberResponse response;
        try {
            response = authServiceClient.updatePhoneNumber(userId,
                    new AuthServiceClient.UpdatePhoneNumberRequest(rawPhoneNumber));
        } catch (ResponseStatusException e) {
            throw translateAuthServiceFailure(e);
        } catch (RuntimeException e) {
            throw phoneNumberUnverifiable(userId, e);
        }

        if (response == null || response.phoneNumber() == null || response.phoneNumber().isBlank()) {
            throw phoneNumberUnverifiable(userId, null);
        }

        return response.phoneNumber();
    }

    private ResponseStatusException translateAuthServiceFailure(ResponseStatusException e) {
        if (e.getStatusCode() == HttpStatus.CONFLICT) {
            return new ResponseStatusException(HttpStatus.CONFLICT,
                    "That phone number is already registered to another account");
        }
        if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
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

    private void requireApplicantIsAdult(LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            return;
        }
        if (Period.between(dateOfBirth, LocalDate.now()).getYears() < MINIMUM_AGE_YEARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You must be at least " + MINIMUM_AGE_YEARS + " years old to open an account.");
        }
    }

    private void autoApproveKycIfEligible(Long userId, UserProfile user) {
        if (!demoAutoApprovalEnabled) {
            return;
        }
        if (user.getKycStatus() != KycStatus.PENDING_VERIFICATION) {
            return;
        }

        processKycWebhook(userId, KycStatus.APPROVED);
    }

    private Map<String, String> captureOldContactState(UserProfile user) {
        Map<String, String> oldState = new HashMap<>();
        oldState.put("legalName", user.getLegalName());
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

    /**
     * Moves a user to a new KYC status and broadcasts the transition.
     *
     * <p>The single point at which a KYC status changes outside a compliance override, used both by
     * the vendor's signed webhook and by the demo auto-approval, so neither can drift from the other.
     *
     * <p>Idempotent: a repeated notification of the status a user already holds returns without
     * writing or publishing, which matters because vendors retry callbacks and a duplicate would
     * otherwise re-notify the user of a change that never happened.
     *
     * <p>The status change and the {@code kyc-events} publish share one transaction, so a failed
     * publish rolls the status back rather than leaving this service believing a user is approved
     * while nothing downstream was told. Called from within {@link #updateContactInfo}, this runs
     * inside that caller's transaction — the approval commits or rolls back together with the
     * identity it was granted on, never on its own.
     *
     * @param userId never {@code null}; must already have a profile row
     * @param newStatus applied as given, including a demotion; this method enforces no transition
     *     rules of its own, so callers that must not clear a {@code REJECTED} verdict have to check
     *     the current status themselves
     * @throws RuntimeException when no profile row exists for {@code userId}
     */
    @Transactional
    public void processKycWebhook(Long userId, KycStatus newStatus) {
        UserProfile user = userProfileRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getKycStatus() == newStatus) {
            return;
        }

        KycStatus oldStatus = user.getKycStatus();
        user.setKycStatus(newStatus);
        userProfileRepository.save(user);

        KycStatusUpdatedEvent event = new KycStatusUpdatedEvent(userId, oldStatus, newStatus, LocalDateTime.now());
        kafkaTemplate.send(KYC_EVENTS_TOPIC, String.valueOf(userId), event);
    }

    /**
     * Forces a user's KYC status on a compliance officer's authority and records why.
     *
     * <p>The audit entry is written before the status is changed, and both share this transaction
     * with the {@code kyc-events} publish: there is no ordering in which a status ends up overridden
     * without an immutable record of who did it and on what grounds. Unlike
     * {@link #processKycWebhook} this applies unconditionally, including re-asserting a status the
     * user already holds, because an override is an event worth auditing even when the value does
     * not move.
     *
     * <p>Emits the same event as the webhook path, so consumers cannot distinguish — and do not need
     * to distinguish — a vendor decision from a manual one.
     *
     * @param userId never {@code null}; must already have a profile row
     * @param adminId never {@code null}; the acting officer from their JWT, recorded in the audit log
     * @param newStatus any status, including clearing a {@code REJECTED} verdict, which is the whole
     *     point of an override
     * @param reason required by the caller and stored verbatim as the audit justification
     * @throws RuntimeException when no profile row exists for {@code userId}
     */
    @Transactional
    public void adminOverrideKyc(Long userId, Long adminId, KycStatus newStatus, String reason) {
        UserProfile user = userProfileRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        KycStatus oldStatus = user.getKycStatus();

        recordOverrideAuditLog(userId, adminId, oldStatus, newStatus, reason);

        user.setKycStatus(newStatus);
        userProfileRepository.save(user);

        KycStatusUpdatedEvent event = new KycStatusUpdatedEvent(userId, oldStatus, newStatus, LocalDateTime.now());
        kafkaTemplate.send(KYC_EVENTS_TOPIC, String.valueOf(userId), event);
    }

    private void recordOverrideAuditLog(Long userId, Long adminId, KycStatus oldStatus, KycStatus newStatus, String reason) {
        KycOverrideAuditLog auditLog = new KycOverrideAuditLog(
                userId, adminId, oldStatus, newStatus, reason
        );
        auditLogRepository.save(auditLog);
    }

    /**
     * Announces a change to a user's contact details on the {@code profile-events} topic.
     *
     * <p>Defined here rather than in a shared library only because there is no shared library yet.
     *
     * @param userId never {@code null}; also used as the partition key so one user's changes stay
     *     ordered
     * @param timestamp when the change was applied, in this service's local zone
     * @param eventType constant discriminator, currently only {@code CONTACT_INFO_CHANGE}
     * @param changes holds {@code old} and {@code new}: the before-values keyed by the JSON property
     *     names of {@code UpdateContactInfoRequestDto}, and the submitted DTO itself. Consumers pair
     *     the two by name, so a key that does not match a DTO property is invisible to them —
     *     notification-service diffs the pair to tell the user what actually changed and
     *     audit-service stores it, making {@code old} the only record of the before-state anywhere.
     *     Values are routinely {@code null} for a profile that was never completed, and dates are
     *     ISO {@code yyyy-MM-dd} to match the shape of the incoming DTO — formatting the two sides
     *     differently would read as a change on every submission that never touched the date
     */
    public record ProfileUpdatedEvent(Long userId, LocalDateTime timestamp, String eventType, Map<String, Object> changes) {}

    /**
     * Announces a KYC transition on the {@code kyc-events} topic.
     *
     * <p>Emitted identically by the vendor webhook, the demo auto-approval, and a compliance
     * override, and only when the status actually moved except in the override case.
     *
     * @param userId never {@code null}; also the partition key
     * @param oldStatus the status held before the change; never equal to {@code newStatus} on the
     *     webhook path
     * @param newStatus the status now in force; transaction-service permits transfers only on
     *     {@code APPROVED}
     * @param timestamp when the transition was applied, in this service's local zone
     */
    public record KycStatusUpdatedEvent(Long userId, KycStatus oldStatus, KycStatus newStatus, LocalDateTime timestamp) {}
}
