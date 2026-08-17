package com.example.transactionservice.aspect;

import com.example.transactionservice.client.ProfileServiceClient;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Refuses any {@code @RequiresKyc} call whose authenticated caller is not identity-verified.
 *
 * <p>Registered as advice rather than as a plain bean, so the gate attaches to a method by
 * annotation alone and cannot drift apart between the transfer and wire paths. Only calls routed
 * through the Spring proxy are advised; a self-invocation inside a bean runs ungated.
 */
@Aspect
@Component
public class KycEnforcementAspect {

    private final ProfileServiceClient profileServiceClient;

    public KycEnforcementAspect(ProfileServiceClient profileServiceClient) {
        this.profileServiceClient = profileServiceClient;
    }

    /**
     * Aborts the intercepted call unless the caller's KYC status is exactly {@code APPROVED}.
     *
     * <p>Advises every method carrying {@code @RequiresKyc} anywhere in the application. It runs
     * before the target body and therefore before the target's transaction is opened, so a refused
     * call leaves nothing partially done and nothing to roll back.
     *
     * <p>Vets the caller only. The user is resolved from the JWT and a recipient has no session
     * here, so a path that credits somebody else's account must check the receiving side itself.
     *
     * <p>Fails closed in both directions: an unknown status and an unreachable profile-service are
     * both refusals, never approvals. The cost is one synchronous profile-service call on the
     * critical path of every money movement.
     *
     * @throws KycRequiredException when the status is known and is anything other than
     *     {@code APPROVED}; surfaces as 403 and the message reaches the user verbatim, so it must
     *     stand on its own and must not name the specific status — "complete your verification" is
     *     false for a rejected applicant, who cannot clear it by resubmitting
     * @throws KycStatusUnavailableException when profile-service returns no usable answer;
     *     surfaces as 503 so the caller can tell "retry shortly" from "finish your verification"
     * @throws SecurityException when there is no authenticated principal on the request at all
     */
    @Before("@annotation(com.example.transactionservice.annotation.RequiresKyc)")
    public void enforceKycStatus() {
        Long userId = resolveAuthenticatedUserId();

        String kycStatus = fetchKycStatus(userId);

        if (!"APPROVED".equals(kycStatus)) {
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
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getClaim("userId");
    }

    private static final String STATUS_UNAVAILABLE_MESSAGE =
            "We couldn't confirm your identity verification right now. Please try again in a moment.";

    private String fetchKycStatus(Long userId) {
        Map<String, String> response;
        try {
            response = profileServiceClient.getKycStatus(userId);
        } catch (RuntimeException e) {
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

    /**
     * Signals a refusal because an identity verification is known not to be approved.
     *
     * <p>Raised for the caller by this aspect and for the payee by
     * {@code RecipientKycValidator}, deliberately sharing one type so a single 403 mapping in
     * {@code GlobalExceptionHandler} covers both. Because two different subjects can trigger it and
     * the frontend now displays whichever message arrives, the message must say <em>whose</em>
     * verification is at issue and must disclose nothing about a third party's standing beyond
     * "you can't pay them yet".
     */
    public static class KycRequiredException extends RuntimeException {
        public KycRequiredException(String message) {
            super(message);
        }
    }

    /**
     * Signals that no verification answer could be obtained, as opposed to a negative one.
     *
     * <p>Deliberately not a subclass of {@link KycRequiredException}. The behaviour is identical —
     * nothing moves either way, and an unreachable dependency is never read as approval — but
     * inheriting would let the 403 handler swallow it and report a verification problem to a user
     * who has none. As a separate type it maps to 503, telling the caller to retry rather than to
     * go and fix their profile.
     *
     * <p>Also reused by the wire path for a failed destination lookup, which means the same thing:
     * a dependency owed us an answer, did not give one, and nothing moved.
     */
    public static class KycStatusUnavailableException extends RuntimeException {
        public KycStatusUnavailableException(String message) {
            super(message);
        }

        public KycStatusUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
