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

@RestController
@RequestMapping("/api/v1")
public class ProfileController {

    private static final Logger logger = LoggerFactory.getLogger(ProfileController.class);

    private final ProfileManagementService profileManagementService;
    private final UserProfileRepository userProfileRepository;

    // app.demo.enabled used to be read here to gate the simulate-approval endpoint. That endpoint is
    // gone and the flag now lives on ProfileManagementService, which is where the approval decision
    // is actually made.

    public ProfileController(ProfileManagementService profileManagementService,
                             UserProfileRepository userProfileRepository) {
        this.profileManagementService = profileManagementService;
        this.userProfileRepository = userProfileRepository;
    }

    @PutMapping("/profiles/me/contact-info")
    public ResponseEntity<?> updateMyContactInfo(@Valid @RequestBody UpdateContactInfoRequestDto dto) {
        // Securely extract the userId from the JWT session, preventing IDOR attacks
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long currentUserId = extractUserIdFromAuth(authentication);

        profileManagementService.updateContactInfo(currentUserId, dto);

        // The resulting KYC status rides back on this response. Submitting this form is what triggers
        // verification now, so the page has to be able to show the outcome immediately - returning it
        // here saves the UI a second round trip and removes the race where it re-reads the status
        // before the approval has committed.
        return ResponseEntity.ok(Map.of(
                "message", "Profile updated successfully",
                "kycStatus", resolveKycStatus(currentUserId)));
    }

    // What the frontend's Profile page calls. Takes no userId at all - it reads the caller's own id
    // out of the JWT, which is both why it can be safely authenticated-only and why one user can no
    // longer look up another's KYC status by guessing an id.
    @GetMapping("/profiles/me/kyc-status")
    @PreAuthorize("hasAuthority('SCOPE_FULL_AUTH')")
    public ResponseEntity<?> getMyKycStatus() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long currentUserId = extractUserIdFromAuth(authentication);

        return ResponseEntity.ok(Map.of("status", resolveKycStatus(currentUserId)));
    }

    // The service-to-service twin of the above, called by transaction-service's KycEnforcementAspect
    // before it lets any money move. It has to stay unauthenticated - there is no end-user token on
    // an internal call - so it lives under /api/v1/internal/, the one prefix the k8s ingress does not
    // route. It previously sat at /api/v1/profiles/{userId}/kyc-status, which the ingress DOES route,
    // publishing "any user's KYC status by id, no credentials required" to the internet.
    // @PathVariable pulls the {userId} segment straight out of the url and hands it to me
    // already converted to a long, spring matches it up by parameter name automatically
    @GetMapping("/internal/profiles/{userId}/kyc-status")
    public ResponseEntity<?> getKycStatus(@PathVariable Long userId) {
        return ResponseEntity.ok(Map.of("status", resolveKycStatus(userId)));
    }

    private String resolveKycStatus(Long userId) {
        UserProfile user = userProfileRepository.findById(userId)
                .orElseGet(() -> provisionMissingProfile(userId));

        return user.getKycStatus().name();
    }

    // A user can hold valid credentials in auth-service and still have no profile row here, if the
    // "user-events" Kafka message that normally provisions one was never consumed (broker down at
    // registration time, or the account predates that fan-out existing at all). This used to throw
    // a 500, which was worse than it looked: transaction-service's KycEnforcementAspect calls this
    // same endpoint before every transfer, so one missing row silently blocked all money movement
    // AND left the Profile page's KYC line blank, with no way for the user to recover on their own.
    // Provisioning on read is idempotent and mirrors exactly what UserRegisteredListener would have
    // created, so the affected user self-heals on their next page load. No @Transactional here: this
    // is called from a lambda inside the same class, so a proxy-based annotation would be bypassed
    // anyway - the single save() carries its own transaction, which is all this needs.
    private UserProfile provisionMissingProfile(Long userId) {
        logger.warn("No profile found for user id {} - provisioning a PENDING_VERIFICATION profile on read. "
                + "This means the user-events message for this user was never consumed.", userId);

        UserProfile profile = new UserProfile();
        profile.setId(userId);
        return userProfileRepository.save(profile);
    }

    @PostMapping("/webhooks/kyc-update")
    public ResponseEntity<?> handleKycWebhook(@RequestBody Map<String, String> payload) {
        // We can safely process this because the KycWebhookFilter verified the HMAC signature
        Long userId = Long.valueOf(payload.get("userId"));
        KycStatus newStatus = KycStatus.valueOf(payload.get("status"));

        profileManagementService.processKycWebhook(userId, newStatus);
        
        // Always return 200 OK immediately so the external vendor knows we received it
        return ResponseEntity.ok().build(); 
    }

    // The "Simulate KYC Approval (Demo)" endpoint that used to live here is gone. It approved a user
    // on a button press with no information collected at all, which meant KYC could be cleared
    // without ever saying who you were. Verification is now driven by PUT /profiles/me/contact-info:
    // supply a legal name, date of birth and address, and ProfileManagementService approves you off
    // the back of that submission - still only when app.demo.enabled is true, exactly as this
    // endpoint was gated.

    // @PreAuthorize runs before the method body even starts, checking the spring expression
    // language string against the logged in user's roles, request never even reaches this
    // code if neither role matches
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

    // =========================================================================
    // Internal Utilities
    // =========================================================================
    
    private Long extractUserIdFromAuth(Authentication authentication) {
        // The JWT subject holds the username, not the id — auth-service puts the numeric
        // userId in its own claim instead, since this service has no User table to resolve it from.
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getClaim("userId");
    }
}