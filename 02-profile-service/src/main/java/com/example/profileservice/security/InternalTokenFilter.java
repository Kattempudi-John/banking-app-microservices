package com.example.profileservice.security;

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
 * Requires a shared secret header on every {@code /api/v1/internal/} request.
 *
 * <p>These endpoints carry no end-user token, so they cannot sit behind the JWT rule. Before this
 * filter the only thing protecting them was the k8s ingress declining to route that prefix, which is
 * one mistake deep: a misrouted ingress rule, an SSRF, or anything already running inside the
 * cluster reaches them directly. Requiring a secret every calling service sends means network
 * placement is no longer the sole defence.
 *
 * <p>Deliberately far cheaper than the sibling {@link KycWebhookFilter}: that one verifies a
 * signature from an outside vendor over a body this service does not control, so it needs an HMAC
 * and a cached body. These callers are our own services, so a shared header suffices — signing
 * bodies here would buy nothing, since the secret is equally shared either way, at the cost of every
 * caller having to sign.
 *
 * <p>Rejections answer {@code 401} with an identical body whether the header was absent or wrong;
 * saying which would confirm to an unauthenticated caller that the header is the thing being checked.
 * The body repeats the reason under both {@code error} and {@code message} because the two error
 * shapes in this project disagree on the key (see {@code GlobalExceptionHandler}).
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    /**
     * Shared secret every internal caller must present.
     *
     * <p>Defaulted so docker-compose and a plain {@code mvn spring-boot:run} work with zero config.
     * Every service in this project reads the same property name and default: a caller and callee
     * disagreeing here fails as a runtime {@code 401}, not a build error.
     */
    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    private static final String INTERNAL_PATH_PREFIX = "/api/v1/internal/";
    private static final String TOKEN_HEADER = "X-Internal-Token";

    private static final String UNAUTHORIZED_BODY =
            "{\"error\":\"Unauthorized internal request\",\"message\":\"Unauthorized internal request\"}";

    /**
     * Passes non-internal requests straight through, and gates internal ones on the shared token.
     *
     * <p>Matching is {@code startsWith}, not the {@code contains} used by {@link KycWebhookFilter}:
     * {@code contains} would also gate any future path that merely mentions the prefix mid-URI,
     * turning an unrelated endpoint into an unexplained {@code 401}.
     *
     * <p>Token comparison runs in constant time. {@code String.equals} bails out on the first
     * mismatched character, so its response time leaks how many leading characters were right —
     * enough to recover the secret one character at a time.
     *
     * @param request URIs outside {@code /api/v1/internal/} are left to the JWT rule and the
     *     webhook's own signature check
     * @param response written with {@code 401} and a JSON body when the token is missing or wrong,
     *     in which case the chain is not continued
     * @param filterChain invoked exactly once, and only on a pass
     */
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

        if (!isTokenValid(request.getHeader(TOKEN_HEADER))) {
            rejectRequest(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isTokenValid(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return false;
        }

        return MessageDigest.isEqual(
                presentedToken.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8));
    }

    private void rejectRequest(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(UNAUTHORIZED_BODY);
    }
}
