package com.example.transactionservice.aspect;

import com.example.transactionservice.client.ProfileServiceClient;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Map;

// @Aspect plus @Component is what turns this class into actual aop advice instead of just a
// plain bean, spring wraps any method carrying @RequiresKyc in a proxy that calls this class first
@Aspect
@Component
public class KycEnforcementAspect {

    private final ProfileServiceClient profileServiceClient;

    public KycEnforcementAspect(ProfileServiceClient profileServiceClient) {
        this.profileServiceClient = profileServiceClient;
    }

    // @Before with this pointcut expression means run before any method anywhere in the app
    // carrying @RequiresKyc, learned this is why executeTransfer and initiateWire both got this
    // check for free just by adding the annotation, no code duplicated between the two services
    @Before("@annotation(com.example.transactionservice.annotation.RequiresKyc)")
    public void enforceKycStatus() {
        // 1. Securely extract the user ID from the active JWT session
        Long userId = resolveAuthenticatedUserId();

        // 2. Make the synchronous network call to the Profile Service
        String kycStatus = fetchKycStatus(userId);

        if (!"APPROVED".equals(kycStatus)) {
            // Throwing this custom exception instantly aborts the intercepted transaction method.
            // A @RestControllerAdvice class will catch this and translate it into a 403 Forbidden HTTP response.
            // Worded for the person reading it, because it is shown to them verbatim. The frontend
            // used to replace every 403 on this path with one hardcoded line about the sender's own
            // identity - which became wrong the moment a second 403 existed, since a transfer is
            // also refused when the RECIPIENT isn't verified, and that told the sender to go and
            // verify themselves when they already were. The frontend now shows whatever reason
            // arrives, so this text has to stand on its own.
            // Deliberately not naming PENDING_VERIFICATION vs REJECTED: "complete your verification"
            // would be false for a rejected applicant, who cannot clear it by resubmitting.
            throw new KycRequiredException(
                    "Transfers are disabled until your identity is verified. "
                            + "Check your Profile page for your verification status.");
        }
    }

    private Long resolveAuthenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new SecurityException("User is not authenticated.");
        }
        if (!authentication.isAuthenticated()) {
            throw new SecurityException("User is not authenticated.");
        }
        // The JWT subject holds the username, not the id — auth-service puts the numeric
        // userId in its own claim instead, since this service has no User table to resolve it from.
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getClaim("userId");
    }

    // Wording the caller sees. Deliberately says nothing about which dependency broke - that the
    // profile service exists at all is our problem, not theirs, and "try again in a moment" is the
    // only action available to them.
    private static final String STATUS_UNAVAILABLE_MESSAGE =
            "We couldn't confirm your identity verification right now. Please try again in a moment.";

    private String fetchKycStatus(Long userId) {
        Map<String, String> response;
        try {
            response = profileServiceClient.getKycStatus(userId);
        } catch (RuntimeException e) {
            // profile-service unreachable, timing out, or answering with an error status (the
            // ErrorDecoder re-throws those locally). Left unhandled this became an HTTP 500, which
            // reads as this service crashing when in fact it correctly refused to proceed.
            throw new KycStatusUnavailableException(STATUS_UNAVAILABLE_MESSAGE, e);
        }

        if (response == null) {
            throw new KycStatusUnavailableException(STATUS_UNAVAILABLE_MESSAGE);
        }
        if (!response.containsKey("status")) {
            throw new KycStatusUnavailableException(STATUS_UNAVAILABLE_MESSAGE);
        }

        return response.get("status");
    }

    // =========================================================================
    // Inner Exception Classes for clarity (usually kept in a separate file)
    // =========================================================================
    public static class KycRequiredException extends RuntimeException {
        public KycRequiredException(String message) {
            super(message);
        }
    }

    // "We know the answer and it's no" (KycRequiredException, 403) versus "we couldn't get an
    // answer" (this one, 503) are genuinely different facts and the caller should be able to tell
    // them apart - one means finish your verification, the other means retry shortly.
    // Deliberately NOT a subclass of KycRequiredException: the behaviour is identical (nothing moves
    // either way, an unreachable dependency is never read as approval) but inheriting would let the
    // existing 403 handler swallow it and put us back at reporting the wrong thing.
    public static class KycStatusUnavailableException extends RuntimeException {
        public KycStatusUnavailableException(String message) {
            super(message);
        }

        public KycStatusUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}