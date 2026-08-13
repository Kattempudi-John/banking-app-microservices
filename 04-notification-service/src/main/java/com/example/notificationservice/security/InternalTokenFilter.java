package com.example.notificationservice.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Second layer under the k8s ingress deliberately not routing /api/v1/internal/. The ingress alone is
// one misrouted path rule, one SSRF, or one compromised pod away from publishing every internal
// endpoint in this service - including the daily-summary trigger, which sends REAL email, so an open
// version of it is both a spam relay and a way to burn the provider quota this account pays for.
//
// Same OncePerRequestFilter base class and same path-scoped shape as profile-service's
// KycWebhookFilter, minus the HMAC and the body caching: nothing here reads the request body, so
// wrapping the request to make it re-readable would be cost with no purpose.
//
// The identical filter, header and property exist in auth-service, profile-service, account-service
// and transaction-service. Do not rename any of the three here without changing all five - a caller
// that still sends the old header just starts getting 401s, and the daily summary is the thing that
// breaks first.
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    // Public because FeignInternalTokenConfig sends this exact header on the way OUT to profile- and
    // account-service. One constant, so the inbound check and the outbound call cannot drift apart.
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private static final String INTERNAL_PATH_PREFIX = "/api/v1/internal/";

    // Deliberately the same wording for a missing header and a wrong one. Telling the two apart
    // hands an attacker a free oracle: "header absent" vs "header wrong" confirms they guessed the
    // header name right, which is the first thing they would need. It also names neither the header
    // nor the property, so a 401 leaks nothing about how to satisfy it.
    private static final String REJECTION_MESSAGE = "Missing or invalid internal service credential";

    // Defaulted so docker-compose and a bare `mvn spring-boot:run` still work with zero config. The
    // default is a known public value - every deployed environment has to override it via
    // APPLICATION_SECURITY_INTERNAL_TOKEN, or this filter is a lock whose key is in the repo.
    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // Everything outside the internal prefix passes straight through untouched. That includes
        // the customer-facing GET /api/v1/notifications, which is gated by its own JWT +
        // @PreAuthorize("hasAuthority('SCOPE_FULL_AUTH')") and must keep working for callers that
        // have no internal token at all.
        if (!request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!isTokenValid(request.getHeader(INTERNAL_TOKEN_HEADER))) {
            rejectRequest(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    // MessageDigest.isEqual compares every byte no matter what. String.equals bails on the first
    // mismatched character, so its response time leaks how much of the token was guessed correctly -
    // enough to recover a secret one character at a time given enough requests.
    private boolean isTokenValid(String presentedToken) {
        if (presentedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presentedToken.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8));
    }

    // Written directly rather than delegating to Spring Security's entry point: this filter runs
    // ahead of the authorization rules, which still say permitAll for this prefix (callers carry no
    // user JWT), so there is no AuthenticationException in flight for an entry point to translate.
    // Both keys carry the same text - the shared contract across all five services.
    private void rejectRequest(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"error\":\"" + REJECTION_MESSAGE + "\",\"message\":\"" + REJECTION_MESSAGE + "\"}");
    }
}
