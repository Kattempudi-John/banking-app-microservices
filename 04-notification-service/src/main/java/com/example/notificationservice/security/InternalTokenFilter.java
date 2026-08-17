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

/**
 * Requires a shared secret header on every request under {@code /api/v1/internal/}, and lets
 * everything else through untouched.
 *
 * <p>This is the second layer under the k8s ingress deliberately not routing that prefix. The
 * ingress alone is one misrouted path rule, one SSRF, or one compromised pod away from publishing
 * every internal endpoint here — including the daily-summary trigger, which sends real email, so an
 * exposed version is both a spam relay and a way to burn the paid provider quota.
 *
 * <p>Requests outside the internal prefix are passed straight through, including the
 * customer-facing {@code GET /api/v1/notifications}, which is gated by its own JWT and
 * {@code @PreAuthorize} and must keep working for callers holding no internal token at all.
 *
 * <p>The identical filter, header and property exist in auth-service, profile-service,
 * account-service and transaction-service. Renaming the header or the property here without changing
 * all five turns every existing caller into a 401, and the daily summary breaks first.
 *
 * <p>Rejections deliberately use one message for a missing header and a wrong one. Distinguishing
 * them hands an attacker an oracle confirming they guessed the header name, and the message names
 * neither the header nor the property, so a 401 leaks nothing about how to satisfy it.
 *
 * <p>{@code application.security.internal-token} is defaulted so docker-compose and a bare
 * {@code mvn spring-boot:run} work with no configuration. That default is a known public value:
 * every deployed environment must override it via {@code APPLICATION_SECURITY_INTERNAL_TOKEN}, or
 * this filter is a lock whose key is in the repository.
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    /**
     * The header carrying the shared internal secret.
     *
     * <p>Public because {@code FeignInternalTokenConfig} sets this exact header on outbound calls to
     * profile- and account-service; one constant means the inbound check and the outbound call
     * cannot drift apart.
     */
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private static final String INTERNAL_PATH_PREFIX = "/api/v1/internal/";

    private static final String REJECTION_MESSAGE = "Missing or invalid internal service credential";

    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

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

    private boolean isTokenValid(String presentedToken) {
        if (presentedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presentedToken.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8));
    }

    private void rejectRequest(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"error\":\"" + REJECTION_MESSAGE + "\",\"message\":\"" + REJECTION_MESSAGE + "\"}");
    }
}
