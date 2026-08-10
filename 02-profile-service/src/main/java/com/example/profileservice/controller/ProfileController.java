package com.example.profileservice.controller;

import com.example.profileservice.dto.UpdateContactInfoRequestDto;
import com.example.profileservice.model.KycStatus;
import com.example.profileservice.model.UserProfile;
import com.example.profileservice.repository.UserProfileRepository;
import com.example.profileservice.service.ProfileManagementService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    // Portfolio-demo affordance: lets the logged-in user flip their own KYC to APPROVED without an
    // admin role (none can be granted today, see README known limitations) or manual DB access.
    // Explicitly off in prod (application-prod.yml) — see AuthController for the same pattern.
    @Value("${app.demo.enabled:false}")
    private boolean demoModeEnabled;

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
        
        return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
    }

    // @PathVariable pulls the {userId} segment straight out of the url and hands it to me
    // already converted to a long, spring matches it up by parameter name automatically
    @GetMapping("/profiles/{userId}/kyc-status")
    public ResponseEntity<?> getKycStatus(@PathVariable Long userId) {
        UserProfile user = userProfileRepository.findById(userId)
                .orElseGet(() -> provisionMissingProfile(userId));

        return ResponseEntity.ok(Map.of("status", user.getKycStatus().name()));
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

    // Simulates the same vendor-webhook callback handleKycWebhook() above receives, but triggered
    // by the user themselves for demo purposes and scoped to their own userId from the JWT only —
    // unlike the admin override below, there is no reason/audit trail since this isn't a real override.
    @PostMapping("/profiles/kyc/simulate-approval")
    @PreAuthorize("hasAuthority('SCOPE_FULL_AUTH')")
    public ResponseEntity<?> simulateKycApproval() {
        if (!demoModeEnabled) {
            return ResponseEntity.notFound().build();
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long userId = extractUserIdFromAuth(authentication);

        profileManagementService.processKycWebhook(userId, KycStatus.APPROVED);

        return ResponseEntity.ok(Map.of("status", KycStatus.APPROVED.name()));
    }

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