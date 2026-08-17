package com.example.accountservice.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Gates {@code /api/v1/internal/**} on a shared secret carried in the {@code X-Internal-Token} header.
 *
 * <p>Those paths are {@code permitAll} at the authorization layer because a service-to-service call
 * carries no end-user JWT, so this filter is the only credential check standing in front of them.
 * Without it, anything able to reach this pod's port could {@code POST} to {@code /credit} and mint
 * money into any account with no credential at all — the k8s ingress declining to route the prefix is
 * a routing decision, not a security boundary.
 *
 * <p>The header name, the property name, and the check itself are duplicated in the other four
 * services and form a cross-service contract. Renaming either name in one service alone breaks every
 * caller of that service.
 *
 * <p>Deliberately lighter than profile-service's {@code KycWebhookFilter}, which HMAC-signs the
 * request body: that one accepts payloads from an outside vendor, where the body contents are what
 * must be trusted. Here both ends are our own services on our own network, so a bearer-style shared
 * secret is the right weight — no body caching, no HMAC.
 *
 * <p>Instantiate this by hand inside the security chain rather than declaring it a
 * {@code @Component}; as a bean, Boot also auto-registers it across the whole servlet chain and it
 * runs twice per request.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    static final String TOKEN_HEADER = "X-Internal-Token";

    private static final String INTERNAL_PATH_PREFIX = "/api/v1/internal/";

    private static final String REJECTION_MESSAGE = "Unauthorized internal service request";

    private final byte[] expectedToken;

    /**
     * Creates a filter that admits only callers presenting exactly {@code internalToken}.
     *
     * @param internalToken never {@code null}; the raw shared secret, compared as UTF-8 bytes, and
     *     expected to be identical to the value every peer service sends
     */
    public InternalTokenFilter(String internalToken) {
        this.expectedToken = internalToken.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Admits a request under the internal prefix only when its token matches, and passes every other
     * request through untouched.
     *
     * <p>Requests outside {@code /api/v1/internal/} — the whole customer-facing JWT API — are none of
     * this filter's business. Inverting that test would demand a service secret from every logged-in
     * user's browser.
     *
     * <p>A rejection is a {@code 401} carrying one fixed message for the missing-token and
     * wrong-token cases alike, naming neither the header nor the property. Distinguishing them would
     * make this an oracle confirming when a guessed token is at least well-formed; callers that
     * legitimately belong here already know what to send. The comparison itself is constant time, so
     * response latency does not leak how many leading bytes of the secret were guessed correctly.
     *
     * @param request never {@code null}; matched on the path with any context path stripped first
     * @param response never {@code null}; written to directly, and the chain stopped, on rejection
     * @param filterChain never {@code null}; invoked only when the request is admitted
     * @throws ServletException when a downstream filter fails
     * @throws IOException when the rejection body cannot be written
     */
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        if (!isInternalRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!isTokenValid(request.getHeader(TOKEN_HEADER))) {
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isInternalRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;
        return path.startsWith(INTERNAL_PATH_PREFIX);
    }

    private boolean isTokenValid(String presentedToken) {
        if (presentedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(presentedToken.getBytes(StandardCharsets.UTF_8), expectedToken);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + REJECTION_MESSAGE + "\",\"message\":\"" + REJECTION_MESSAGE + "\"}");
    }
}
