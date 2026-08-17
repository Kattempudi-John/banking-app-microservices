package com.example.accountservice.aspect;

import com.example.accountservice.client.ProfileServiceClient;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Refuses {@code @RequiresKyc} service methods unless the calling user's KYC status is
 * {@code APPROVED}.
 *
 * <p>This is the same gate transaction-service puts in front of moving money, applied here to
 * opening an account and paying money in. It advises the annotation, not any particular class, so
 * gating a further operation later is one annotation rather than another copy of this check.
 *
 * <p>Interception happens on the Spring proxy, so only calls that cross the proxy boundary are
 * gated. Entry points that build accounts directly — notably the Kafka listener that provisions a
 * starter account at registration — bypass it deliberately, which is what lets a brand-new user be
 * provisioned while still at {@code PENDING_VERIFICATION}.
 *
 * <p>The gate fails <b>closed</b>. When profile-service cannot answer, the intercepted method is
 * aborted rather than allowed through: a gate that opens when its dependency is down is not a gate.
 */
@Aspect
@Component
public class KycEnforcementAspect {

    private static final String KYC_UNAVAILABLE_MESSAGE =
            "We couldn't confirm your identity verification right now. Please try again in a moment.";

    private final ProfileServiceClient profileServiceClient;

    public KycEnforcementAspect(ProfileServiceClient profileServiceClient) {
        this.profileServiceClient = profileServiceClient;
    }

    /**
     * Checks the caller's KYC status and aborts the intercepted method when it is not approved.
     *
     * <p>Runs before the advised method body, so nothing it would have written is applied — no
     * account is created and no deposit row is recorded on either failure path.
     *
     * <p>The user is resolved from the {@code userId} claim on the JWT in the security context,
     * never from an argument of the advised method, since an argument could name somebody else.
     * A method reachable with no authenticated caller therefore fails here rather than passing.
     *
     * @throws SecurityException when there is no authenticated caller in the security context
     * @throws KycRequiredException when profile-service answered with any status other than
     *     {@code APPROVED}; the message carries that status so the UI can name the blocking state
     * @throws KycUnavailableException when profile-service could not be reached or answered
     *     without a status
     */
    @Before("@annotation(com.example.accountservice.annotation.RequiresKyc)")
    public void enforceKycStatus() {
        Long userId = resolveAuthenticatedUserId();

        String kycStatus = fetchKycStatus(userId);

        if (!"APPROVED".equals(kycStatus)) {
            throw new KycRequiredException("Action forbidden: KYC verification is " + kycStatus
                    + ". Approved status required to open an account or add funds.");
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
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getClaim("userId");
    }

    private String fetchKycStatus(Long userId) {
        Map<String, String> response = callProfileService(userId);

        if (response == null) {
            throw new KycUnavailableException(KYC_UNAVAILABLE_MESSAGE);
        }
        if (!response.containsKey("status")) {
            throw new KycUnavailableException(KYC_UNAVAILABLE_MESSAGE);
        }

        return response.get("status");
    }

    private Map<String, String> callProfileService(Long userId) {
        try {
            return profileServiceClient.getKycStatus(userId);
        } catch (RuntimeException ex) {
            throw new KycUnavailableException(KYC_UNAVAILABLE_MESSAGE, ex);
        }
    }

    /**
     * Signals that the caller's identity was checked and is not approved.
     *
     * <p>{@code GlobalExceptionHandler} maps this to a 403 carrying the offending status name, so
     * the UI can tell the user which state is blocking them and that finishing verification
     * resolves it.
     */
    public static class KycRequiredException extends RuntimeException {
        public KycRequiredException(String message) {
            super(message);
        }
    }

    /**
     * Signals that the caller's identity could not be checked at all.
     *
     * <p>A distinct type from {@link KycRequiredException} because the two are different answers,
     * not different wordings: this one is nobody's fault and is not resolved by completing
     * verification. {@code GlobalExceptionHandler} maps it to a 503 rather than a 403, and the
     * message is deliberately silent about profile-service — the user cannot act on which internal
     * service is down, and naming internal topology in a browser-readable response is free
     * reconnaissance.
     *
     * <p>Both exceptions abort the advised method, so the gate stays fail-closed either way.
     */
    public static class KycUnavailableException extends RuntimeException {
        public KycUnavailableException(String message) {
            super(message);
        }

        public KycUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
