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

// Second layer in front of every /api/v1/internal/ endpoint. Until now the ONLY thing keeping these
// unauthenticated - they carry no end-user token, so they cannot be behind the JWT rule - was the
// k8s ingress declining to route that prefix. That is one mistake deep: a misrouted ingress rule, an
// SSRF, or anything already running inside the cluster reaches them directly. A shared secret every
// calling service sends means network placement is no longer the only thing standing there.
//
// Same OncePerRequestFilter shape as KycWebhookFilter next door, and deliberately much less work than
// it: the webhook is signed by an outside vendor over a body this service does not control, so it
// needs an HMAC and a cached body. These callers are our own services, so a shared header is enough.
// The rejected alternative was reusing the HMAC scheme here, which would have bought nothing (the
// secret is equally shared either way) at the cost of every caller having to sign its request body.
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    // Defaulted so docker-compose and a plain `mvn spring-boot:run` still work with zero config.
    // Every service in this project reads the SAME property name and default - a caller and a callee
    // disagreeing here fails as a 401 at runtime, not at build time, so the names have to match.
    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    private static final String INTERNAL_PATH_PREFIX = "/api/v1/internal/";
    private static final String TOKEN_HEADER = "X-Internal-Token";

    // One message for "no header" and "wrong header" alike: telling an unauthenticated caller WHICH
    // of the two it got wrong confirms for them that the header is the thing being checked and turns
    // guessing into a two-step problem. For the same reason it names neither the header nor the
    // config property - a legitimate caller is a service we configure ourselves, and it learns this
    // from the contract, not from the error body.
    // Both "error" and "message" carry it because the two error shapes in this project disagree on
    // the key (see GlobalExceptionHandler) and a caller reading either one should see the reason.
    private static final String UNAUTHORIZED_BODY =
            "{\"error\":\"Unauthorized internal request\",\"message\":\"Unauthorized internal request\"}";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. Everything outside the internal prefix is none of this filter's business - the
        // customer-facing endpoints are gated by their JWT, and the KYC webhook by its own signature.
        // startsWith rather than KycWebhookFilter's contains(): contains() would also gate any future
        // path that merely mentions the prefix somewhere in the middle, and a gate that fires on
        // paths nobody meant to protect is a 401 waiting to happen on an unrelated endpoint.
        if (!request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. Under the prefix, a valid token is the entrance fee.
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

        // MessageDigest.isEqual compares every byte no matter what. String.equals() bails out on the
        // first mismatched character, so its response time leaks how many leading characters were
        // right - enough to recover the secret one character at a time. Same reasoning as
        // KycWebhookFilter's signature check.
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
