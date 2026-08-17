package com.example.profileservice.controller;

import com.example.profileservice.dto.UpdateContactInfoRequestDto;
import com.example.profileservice.model.KycStatus;
import com.example.profileservice.model.UserProfile;
import com.example.profileservice.repository.UserProfileRepository;
import com.example.profileservice.service.ProfileManagementService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Exposes a user's identity details and KYC status.
 *
 * <p>Three audiences share this controller and each is authorized differently: the customer-facing
 * {@code /profiles/me} endpoints take no user id at all and read the caller's own id from the JWT,
 * so no one can reach another person's identity by changing a number; the identity vendor's webhook
 * is authenticated by the HMAC signature {@code KycWebhookFilter} verifies before the request
 * arrives; and the {@code /internal/} lookup is authorized by the shared token
 * {@code InternalTokenFilter} requires, since a service-to-service call carries no end-user token.
 *
 * <p>Anything under {@code /api/v1/internal/} is deliberately outside the prefix the k8s ingress
 * routes. An unauthenticated endpoint on a routed prefix is an endpoint published to the internet,
 * which is exactly what "any user's KYC status by id, no credentials required" used to be.
 */
@RestController
@RequestMapping("/api/v1")
public class ProfileController {

    private static final Logger logger = LoggerFactory.getLogger(ProfileController.class);

    private final ProfileManagementService profileManagementService;
    private final UserProfileRepository userProfileRepository;

    public ProfileController(ProfileManagementService profileManagementService,
                             UserProfileRepository userProfileRepository) {
        this.profileManagementService = profileManagementService;
        this.userProfileRepository = userProfileRepository;
    }

    /**
     * Accepts a submitted identity for the authenticated user and answers with the resulting KYC
     * status.
     *
     * <p>Requires {@code SCOPE_FULL_AUTH} for a harder reason than the reads below it. Merely
     * requiring a validly-signed token would admit a {@code PRE_AUTH} token — the one auth-service
     * issues after checking a password but before the 2FA code — so someone holding a stolen password
     * alone could submit this form. This single call both approves the submitted identity and pushes
     * the phone number into auth-service, which is where 2FA codes are delivered: it would move the
     * second factor onto the attacker's phone and hand them the account permanently, KYC approval
     * included. A session that has not finished proving who it is does not get to say who it is.
     *
     * <p>The user id is read from the JWT and never from the request, so this endpoint cannot be
     * pointed at another user.
     *
     * @param dto validated before the body runs; a constraint failure answers {@code 400} with the
     *     constraint's own message
     * @return {@code 200} carrying {@code message} and the post-submission {@code kycStatus}. The
     *     status rides back on this response because submitting the form is what triggers
     *     verification: it saves the page a second round trip and removes the race where the UI
     *     re-reads the status before the approval has committed
     * @throws org.springframework.web.server.ResponseStatusException {@code 400} for an applicant
     *     under 18 or an unresolvable phone number, {@code 409} when that number belongs to another
     *     account, {@code 503} when auth-service cannot confirm it — in each case nothing is saved
     *     and the user is not approved
     */
    @PutMapping("/profiles/me/contact-info")
    @PreAuthorize("hasAuthority('SCOPE_FULL_AUTH')")
    public ResponseEntity<?> updateMyContactInfo(@Valid @RequestBody UpdateContactInfoRequestDto dto) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long currentUserId = extractUserIdFromAuth(authentication);

        profileManagementService.updateContactInfo(currentUserId, dto);

        return ResponseEntity.ok(Map.of(
                "message", "Profile updated successfully",
                "kycStatus", resolveKycStatus(currentUserId)));
    }

    /**
     * Returns the authenticated user's identity details for pre-filling the profile form.
     *
     * <p>Exists because the form used to open blank, so users retyped a phone number from memory —
     * often a different one from the number they registered with. That is the root cause of two
     * accounts landing on the same number, and no amount of write-path validation fixes a form that
     * invites the wrong answer.
     *
     * <p>Accepts no user id: it comes from the JWT, so nobody can read another person's legal name or
     * date of birth by changing one.
     *
     * @return {@code 200} always, with every field {@code null} for a user who has never completed
     *     the form. The phone number comes from auth-service, which owns it, and silently falls back
     *     to this service's possibly-stale copy when auth-service is unreachable
     */
    @GetMapping("/profiles/me/contact-info")
    @PreAuthorize("hasAuthority('SCOPE_FULL_AUTH')")
    public ResponseEntity<?> getMyContactInfo() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long currentUserId = extractUserIdFromAuth(authentication);

        return ResponseEntity.ok(profileManagementService.getContactInfo(currentUserId));
    }

    /**
     * Returns the authenticated user's KYC status.
     *
     * <p>Takes no user id — it reads the caller's own from the JWT, which is both why it is safe to
     * gate on authentication alone and why one user can no longer look up another's status by
     * guessing an id.
     *
     * @return {@code 200} with {@code status} as a {@link KycStatus} name; a user with no profile row
     *     is provisioned one on the spot and reads back {@code PENDING_VERIFICATION} rather than
     *     failing
     */
    @GetMapping("/profiles/me/kyc-status")
    @PreAuthorize("hasAuthority('SCOPE_FULL_AUTH')")
    public ResponseEntity<?> getMyKycStatus() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long currentUserId = extractUserIdFromAuth(authentication);

        return ResponseEntity.ok(Map.of("status", resolveKycStatus(currentUserId)));
    }

    /**
     * Returns any user's KYC status for a calling service.
     *
     * <p>The service-to-service twin of {@link #getMyKycStatus}, called by transaction-service's
     * {@code KycEnforcementAspect} before it lets any money move — so this sits on the critical path
     * of every transfer. It carries no end-user token and so cannot be behind the JWT rule; it is
     * authorized instead by the shared internal token and kept off the ingress-routed prefix.
     *
     * @param userId never {@code null}; taken from the path, so unlike the {@code /me} endpoints this
     *     one can be pointed at any user and must never be exposed to end users
     * @return {@code 200} with {@code status} as a {@link KycStatus} name; a user with no profile row
     *     is provisioned one rather than answered with an error, since failing here would block that
     *     user's transfers outright
     */
    @GetMapping("/internal/profiles/{userId}/kyc-status")
    public ResponseEntity<?> getKycStatus(@PathVariable Long userId) {
        return ResponseEntity.ok(Map.of("status", resolveKycStatus(userId)));
    }

    private String resolveKycStatus(Long userId) {
        UserProfile user = userProfileRepository.findById(userId)
                .orElseGet(() -> provisionMissingProfile(userId));

        return user.getKycStatus().name();
    }

    private UserProfile provisionMissingProfile(Long userId) {
        logger.warn("No profile found for user id {} - provisioning a PENDING_VERIFICATION profile on read. "
                + "This means the user-events message for this user was never consumed.", userId);

        UserProfile profile = new UserProfile();
        profile.setId(userId);
        return userProfileRepository.save(profile);
    }

    /**
     * Applies a KYC decision pushed by the identity vendor.
     *
     * <p>Unauthenticated by design and safe only because {@code KycWebhookFilter} has already
     * verified an HMAC signature over the raw body; without that filter in front, this endpoint would
     * let anyone approve any user. In a real deployment this callback is the only thing that
     * approves anyone.
     *
     * <p>Repeated deliveries are harmless: a decision matching the user's current status is a no-op
     * rather than a second notification.
     *
     * @param payload must carry {@code userId} parsable as a {@code Long} and {@code status} naming a
     *     {@link KycStatus} constant; anything else raises an unhandled parse failure
     * @return an empty {@code 200}, returned as soon as the update is applied so the vendor sees the
     *     acknowledgement it needs and does not retry
     */
    @PostMapping("/webhooks/kyc-update")
    public ResponseEntity<?> handleKycWebhook(@RequestBody Map<String, String> payload) {
        Long userId = Long.valueOf(payload.get("userId"));
        KycStatus newStatus = KycStatus.valueOf(payload.get("status"));

        profileManagementService.processKycWebhook(userId, newStatus);

        return ResponseEntity.ok().build();
    }

    /**
     * Overrides a user's KYC status on compliance authority, recording the acting officer and reason.
     *
     * <p>Restricted to {@code ADMIN} and {@code COMPLIANCE_OFFICER}; the check runs before the method
     * body, so a caller holding neither role never reaches this code. This is the only path that can
     * clear a {@code REJECTED} verdict — the applicant's own resubmissions cannot.
     *
     * @param userId never {@code null}; the user being overridden, not the caller
     * @param payload must carry {@code status} naming a {@link KycStatus} constant and a non-blank
     *     {@code reason}; the reason is mandatory because it is the audit record's only justification
     * @return {@code 200} on success, or {@code 400} when {@code reason} is absent or blank, in which
     *     case no status is changed and nothing is audited
     */
    @PatchMapping("/admin/profiles/{userId}/kyc")
    @PreAuthorize("hasAnyRole('ADMIN', 'COMPLIANCE_OFFICER')")
    public ResponseEntity<?> adminOverrideKyc(@PathVariable Long userId,
                                              @RequestBody Map<String, String> payload) {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long adminId = extractUserIdFromAuth(authentication);

        KycStatus newStatus = KycStatus.valueOf(payload.get("status"));
        String reason = payload.get("reason");

        if (reason == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Override reason is mandatory"));
        }
        if (reason.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Override reason is mandatory"));
        }

        profileManagementService.adminOverrideKyc(userId, adminId, newStatus, reason);

        return ResponseEntity.ok(Map.of("message", "KYC status manually overridden by compliance officer"));
    }

    private Long extractUserIdFromAuth(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getClaim("userId");
    }
}
