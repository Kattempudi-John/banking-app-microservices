package com.example.authservice.security;

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
 * Gates {@code /api/v1/internal/} behind a shared secret carried in {@code X-Internal-Token}.
 *
 * <p>That whole prefix is {@code permitAll} in the security config, and has to be: the callers
 * are other services, which have no end-user JWT to present, so requiring authentication there
 * would break every internal call rather than protect it. Before this filter the only thing
 * keeping the prefix off the internet was the ingress declining to route it, which is a single
 * layer that one bad ingress rule, an SSRF, or anything already inside the network removes. The
 * write behind that prefix changes where an account's 2FA codes are delivered, so losing that
 * layer is an account takeover rather than an information leak.
 *
 * <p>Registered ahead of {@code JwtAuthenticationFilter} so an unauthorized internal call is
 * turned away before any token parsing or database work happens. The two are anchored to each
 * other rather than both to a Spring filter, where they would share an order value and the
 * winner would come down to list order.
 *
 * <p>Held to a shared secret rather than an HMAC signature, unlike profile-service's KYC webhook
 * filter, because a header needs no body read; the request input stream is left untouched for
 * the controller.
 *
 * <p>{@code application.security.internal-token} carries an inline development default so
 * docker-compose and local runs need no configuration, the same way the JWT signing key does.
 * Every real environment must override it, since the default is in the source.
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    private static final String INTERNAL_PATH = "/api/v1/internal/";
    private static final String TOKEN_HEADER = "X-Internal-Token";

    private static final String REJECTION_MESSAGE = "Not authorized to call this endpoint";

    /**
     * Demands the shared secret on internal paths and lets every other request through untouched.
     *
     * <p>Paths outside the internal prefix are forwarded without inspection, so this filter never
     * alters what {@code JwtAuthenticationFilter} sees on a customer request, pre-auth boundary
     * checks included. The prefix is matched with {@code contains} rather than
     * {@code startsWith}, matching the sibling filter and erring towards challenging: the cost of
     * the looser check is an unnecessary {@code 401}, whereas a check that misses, say because a
     * context path was prepended, would silently leave the gate open.
     *
     * <p>A missing token and a wrong token are answered identically, with a {@code 401} that
     * names neither the header nor the property, so the response cannot be used to discover which
     * credential to go looking for. The comparison itself is constant-time for a given token
     * length: an early-exit equality check would let a patient caller time the rejections and
     * recover the secret one character at a time.
     *
     * @param request only its URI and the {@code X-Internal-Token} header are read; the body is
     *     never consumed
     * @param response written to only on rejection
     * @param filterChain continued for every request that is either outside the prefix or
     *     carrying the correct secret
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
