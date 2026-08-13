package com.example.accountservice.aspect;

import com.example.accountservice.client.ProfileServiceClient;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Map;

// The same gate transaction-service puts in front of moving money, applied here to creating an
// account and depositing into one. Opening accounts and taking in funds for an unverified identity
// is exactly what KYC exists to prevent, so "these are demo buttons" isn't a reason to leave them
// ungated - the check is what's being demonstrated.
@Aspect
@Component
public class KycEnforcementAspect {

    // Deliberately says nothing about profile-service. The user cannot act on which of our
    // services is down, and naming internal topology in a response the browser can read is
    // free reconnaissance.
    private static final String KYC_UNAVAILABLE_MESSAGE =
            "We couldn't confirm your identity verification right now. Please try again in a moment.";

    private final ProfileServiceClient profileServiceClient;

    public KycEnforcementAspect(ProfileServiceClient profileServiceClient) {
        this.profileServiceClient = profileServiceClient;
    }

    // Matches any method carrying @RequiresKyc, so gating a new endpoint later is one annotation
    // rather than another copy of this check.
    @Before("@annotation(com.example.accountservice.annotation.RequiresKyc)")
    public void enforceKycStatus() {
        Long userId = resolveAuthenticatedUserId();

        String kycStatus = fetchKycStatus(userId);

        if (!"APPROVED".equals(kycStatus)) {
            // Aborts the intercepted method before it ever runs. GlobalExceptionHandler turns this
            // into a 403 carrying the status name, so the UI can say which state is blocking them.
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
        // Resolved from the token rather than from any method argument on purpose: the caller's own
        // KYC status is the one that has to be checked, and an argument could name someone else.
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getClaim("userId");
    }

    private String fetchKycStatus(Long userId) {
        Map<String, String> response = callProfileService(userId);

        // No status means profile-service could not answer. Failing here rather than defaulting to
        // "approved" - a KYC gate that opens when its dependency is down is not a gate.
        // What changed is only what the caller is TOLD: these used to be a bare RuntimeException,
        // which nothing mapped, so the client saw a 500 and read it as "account-service crashed"
        // when in fact the gate had done its job and correctly refused to proceed.
        if (response == null) {
            throw new KycUnavailableException(KYC_UNAVAILABLE_MESSAGE);
        }
        if (!response.containsKey("status")) {
            throw new KycUnavailableException(KYC_UNAVAILABLE_MESSAGE);
        }

        return response.get("status");
    }

    // A profile-service that is entirely unreachable surfaces as a Feign exception rather than a
    // null body, so the call is wrapped: without this the connection failure escaped the same way
    // the bare RuntimeException used to, and only the null/missing-status cases would have been
    // fixed. Catching RuntimeException rather than FeignException on purpose - a decode failure or
    // a timeout unwrapped by the client is the same situation from the caller's point of view, and
    // an unrecognised failure defaulting back to a 500 would put us back where we started.
    private Map<String, String> callProfileService(Long userId) {
        try {
            return profileServiceClient.getKycStatus(userId);
        } catch (RuntimeException ex) {
            throw new KycUnavailableException(KYC_UNAVAILABLE_MESSAGE, ex);
        }
    }

    public static class KycRequiredException extends RuntimeException {
        public KycRequiredException(String message) {
            super(message);
        }
    }

    // Separate type from KycRequiredException because the two are different answers, not different
    // messages: "we checked and you are not approved" is a 403 the user can resolve by finishing
    // verification, while this one means we could not check at all. Both still abort the intercepted
    // method, so the gate stays fail-closed - no account is opened and no deposit is written either way.
    public static class KycUnavailableException extends RuntimeException {
        public KycUnavailableException(String message) {
            super(message);
        }

        public KycUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
