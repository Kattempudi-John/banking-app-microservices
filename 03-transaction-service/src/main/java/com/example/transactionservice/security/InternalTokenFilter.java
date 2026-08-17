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

/**
 * Gates {@code /api/v1/internal/**} on a shared secret carried in the {@code X-Internal-Token}
 * header.
 *
 * <p>Those paths are {@code permitAll} in {@code SecurityConfig} because their callers are other
 * services holding no end-user JWT. Before this filter the only thing keeping the prefix off the
 * internet was the ingress declining to route it — a single layer that one bad ingress rule, an
 * SSRF, or any foothold inside the network removes. Behind the prefix sits
 * {@code PATCH /api/v1/internal/transfers/{id}/fraud-status}, which approves or reverses a held
 * wire, so losing that layer lets an arbitrary caller release someone else's money.
 *
 * <p>Must be registered ahead of the bearer-token filter in the chain; see
 * {@code SecurityConfig#securityFilterChain}. Requests outside the internal prefix pass straight
 * through, so customer-facing paths are authenticated exactly as they would be without it.
 *
 * <p>Rejections are deliberately uninformative and constant-time: a missing token and a wrong one
 * produce the identical body, which names neither the header nor the property, and the comparison
 * runs over every byte so response timing cannot be used to recover the secret one character at a
 * time.
 *
 * <p>Configured by {@code application.security.internal-token}, which defaults to a development
 * value so local and compose runs work unconfigured; every environment that matters overrides it,
 * and all services in the platform read the same property and header names.
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    private static final String INTERNAL_PATH = "/api/v1/internal/";
    private static final String TOKEN_HEADER = "X-Internal-Token";

    private static final String REJECTION_MESSAGE = "Not authorized to call this endpoint";

    /**
     * Lets any request outside the internal prefix through untouched, and challenges the rest.
     *
     * <p>Path matching uses {@code contains} rather than {@code startsWith}, matching the sibling
     * services' filters and erring toward challenging: a looser check costs a spurious 401, while a
     * check that misses — a context path prepended, say — leaves the gate open silently. A caller
     * that fails the check gets a 401 and the chain stops; the request body is never read, so the
     * stream reaches the controller intact.
     */
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        if (!request.getRequestURI().contains(INTERNAL_PATH)) {
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
        if (presentedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presentedToken.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void rejectRequest(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\": \"" + REJECTION_MESSAGE + "\", \"message\": \"" + REJECTION_MESSAGE + "\"}");
    }
}
