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

// Second layer in front of /api/v1/internal/**, which is permitAll at the authorize layer because
// there is no end-user JWT on a service-to-service call. Until now the only thing keeping those
// endpoints private was the k8s ingress not routing that path prefix - so anything that could reach
// this pod's port could POST to /credit and mint money into any account with no credential at all.
// The same shared-secret check exists in the other four services, header and property names included;
// they are a contract, so do not rename one of them here alone.
//
// Same OncePerRequestFilter base class as profile-service's KycWebhookFilter, but deliberately much
// simpler: that one signs the request BODY with an HMAC because the payload comes from an outside
// vendor and its contents are what must be trusted. Here both ends are our own services on our own
// network, so a shared bearer-style secret is the right weight - no body caching, no HMAC.
public class InternalTokenFilter extends OncePerRequestFilter {

    static final String TOKEN_HEADER = "X-Internal-Token";

    private static final String INTERNAL_PATH_PREFIX = "/api/v1/internal/";

    // One message for the missing case and the wrong case alike. Telling a caller WHICH of the two
    // it got turns this into an oracle that confirms when a guessed token is at least well-formed,
    // and naming the header or the property here would hand an attacker the shape of the mechanism
    // they are probing. Callers that legitimately belong here already know what to send.
    private static final String REJECTION_MESSAGE = "Unauthorized internal service request";

    private final byte[] expectedToken;

    public InternalTokenFilter(String internalToken) {
        this.expectedToken = internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // Everything outside the internal prefix - the whole customer-facing JWT API - is none of
        // this filter's business and passes straight through untouched. Getting this backwards would
        // demand a service secret from every logged-in user's browser.
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

    // Strips the context path before matching so the check keeps working if this service is ever
    // deployed under one - getRequestURI() includes it, so a bare startsWith would silently stop
    // matching and leave every internal endpoint ungated again, failing OPEN rather than closed.
    private boolean isInternalRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;
        return path.startsWith(INTERNAL_PATH_PREFIX);
    }

    // MessageDigest.isEqual, never String.equals: equals() returns on the first differing byte, so
    // how long the comparison took leaks how many leading bytes were right, and an attacker can walk
    // the secret out one byte at a time. isEqual always reads every byte regardless.
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
        // Both keys carry the same text, matching GlobalExceptionHandler and the other services:
        // the frontend's extractApiError reads "error", the service-to-service clients read "message".
        response.getWriter().write("{\"error\":\"" + REJECTION_MESSAGE + "\",\"message\":\"" + REJECTION_MESSAGE + "\"}");
    }
}
