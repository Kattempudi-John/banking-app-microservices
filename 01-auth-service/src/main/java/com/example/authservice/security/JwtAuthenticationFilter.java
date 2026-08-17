package com.example.authservice.security;

import com.example.authservice.repository.BlacklistedTokenRepository;
import com.example.authservice.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Establishes who a request is from, and refuses tokens that are revoked or not yet fully
 * authenticated.
 *
 * <p>Registered ahead of {@code UsernamePasswordAuthenticationFilter} in the chain, because it
 * has to populate the security context before Spring's own authentication machinery decides the
 * request is anonymous. {@code InternalTokenFilter} runs ahead of this one and passes every
 * customer-facing path straight through, so nothing it does changes what this filter sees.
 *
 * <p>Two checks here have no equivalent anywhere else in the platform and cannot be moved into
 * a config rule. The blacklist lookup is what makes logout meaningful for the remaining life of
 * an access token, since a signed token is otherwise valid until it expires. The pre-auth
 * boundary is what stops a token issued before 2FA from being used as a full session: it is
 * enforced by request path, so it only holds for as long as the 2FA endpoints stay under
 * {@code /api/v1/auth/verify-2fa}.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final BlacklistedTokenRepository blacklistedTokenRepository;

    public JwtAuthenticationFilter(JwtService jwtService, 
                                   UserDetailsService userDetailsService, 
                                   BlacklistedTokenRepository blacklistedTokenRepository) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.blacklistedTokenRepository = blacklistedTokenRepository;
    }

    /**
     * Authenticates the bearer token on a request, or answers the request itself and stops the
     * chain.
     *
     * <p>A request with no {@code Authorization} header, or one that is not a {@code Bearer}
     * header, is passed along untouched rather than rejected. That is what lets the
     * {@code permitAll} endpoints, login and register among them, work at all; authorization is
     * decided later in the chain, not here.
     *
     * <p>Three outcomes end the request here instead of forwarding it: a token whose {@code jti}
     * is blacklisted gets {@code 401}, a {@code PRE_AUTH} token presented on any path outside
     * {@code /api/v1/auth/verify-2fa} gets {@code 403}, and any parsing or signature failure gets
     * {@code 401}. That last catch is broad on purpose, so a malformed token can never fall
     * through into the chain as an unauthenticated request that some other rule then permits.
     *
     * <p>The path check uses the request URI so it behaves identically under MockMvc and Tomcat;
     * a servlet context path prefixed to the URI would not break it, since it matches on a
     * substring.
     *
     * @param request read for its {@code Authorization} header and its URI, never modified
     * @param response written to only on rejection, in which case the chain is not continued
     * @param filterChain continued exactly once unless the request was rejected above
     */
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String username;

        if (authHeader == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        jwt = authHeader.substring(7);

        try {
            username = jwtService.extractUsername(jwt);
            String jti = jwtService.extractJti(jwt);
            TokenType tokenType = jwtService.extractTokenType(jwt);

            if (isBlacklisted(jti)) {
                writeErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Token has been revoked. Please log in again.");
                return;
            }

            String requestPath = request.getRequestURI();
            if (violatesPreAuthBoundary(tokenType, requestPath)) {
                writeErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "Partial authentication. 2FA verification required.");
                return;
            }

            authenticateIfNeeded(request, username, jwt);
        } catch (Exception e) {
            writeErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isBlacklisted(String jti) {
        return blacklistedTokenRepository.existsById(jti);
    }

    private boolean violatesPreAuthBoundary(TokenType tokenType, String requestPath) {
        if (tokenType != TokenType.PRE_AUTH) {
            return false;
        }
        return !requestPath.contains("/api/v1/auth/verify-2fa");
    }

    private void authenticateIfNeeded(HttpServletRequest request, String username, String jwt) {
        if (username == null) {
            return;
        }
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            return;
        }

        UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);

        if (jwtService.isTokenValid(jwt, userDetails)) {
            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.getAuthorities()
            );
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authToken);
        }
    }

    private void writeErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}