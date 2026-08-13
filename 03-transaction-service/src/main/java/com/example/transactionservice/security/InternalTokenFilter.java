package com.example.transactionservice.security;

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

// /api/v1/internal/** is permitAll in SecurityConfig because the callers are other services, which
// hold no end-user JWT to present. Until now the only thing keeping that prefix away from the
// internet was the k8s ingress declining to route it - one layer, and a single bad ingress rule (or
// an SSRF, or anything already inside the network) removes it. What sits behind the prefix here is
// PATCH /api/v1/internal/transfers/{id}/fraud-status, which approves or reverses a held wire: losing
// that one layer means an arbitrary caller can release someone else's money, not just read it.
//
// Same OncePerRequestFilter base as profile-service's KycWebhookFilter, and deliberately simpler
// than it - a shared secret arriving in a header needs no HMAC and no body read, so the request
// stream is left untouched and no caching wrapper is needed.
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    // Colon default keeps docker-compose and local runs working with zero configuration, the same
    // way SecurityConfig defaults the JWT signing key. Real environments override it from the k8s
    // secret. All five services read this same property name and header.
    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    private static final String INTERNAL_PATH = "/api/v1/internal/";
    private static final String TOKEN_HEADER = "X-Internal-Token";

    // Deliberately says nothing about which header or property was wrong. A caller who can reach
    // this endpoint at all should not be handed the name of the credential to go looking for, and
    // missing/wrong are answered identically so the response can't be used to probe for the schema.
    private static final String REJECTION_MESSAGE = "Not authorized to call this endpoint";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. Only intercept the internal prefix - every customer-facing path passes straight
        // through untouched, so the oauth2 resource server's bearer-token handling still sees those
        // requests exactly as it did before this filter existed.
        // contains() rather than startsWith() to match the sibling services' filters, and because it
        // errs towards challenging a request: the failure mode of the looser check is an extra 401,
        // while a check that misses (a context path prepended, say) silently leaves the gate open.
        if (!request.getRequestURI().contains(INTERNAL_PATH)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. Compare the presented secret against the configured one
        if (!isTokenValid(request.getHeader(TOKEN_HEADER))) {
            rejectRequest(response);
            return;
        }

        // 3. Caller proved it holds the shared secret - hand it on to the controller
        filterChain.doFilter(request, response);
    }

    // String.equals() returns the moment it hits a mismatched character, so how long the rejection
    // took leaks how many leading characters were right, and a patient caller can recover the secret
    // one character at a time. MessageDigest.isEqual compares every byte regardless, so every wrong
    // token of a given length costs the same.
    private boolean isTokenValid(String presentedToken) {
        if (presentedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presentedToken.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    // Both keys carry the same text, matching GlobalExceptionHandler's own two-key error body:
    // "error" is what this service has always returned and what the frontend reads, "message" is
    // what the calling services read - all five services answer this identically.
    private void rejectRequest(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\": \"" + REJECTION_MESSAGE + "\", \"message\": \"" + REJECTION_MESSAGE + "\"}");
    }
}
