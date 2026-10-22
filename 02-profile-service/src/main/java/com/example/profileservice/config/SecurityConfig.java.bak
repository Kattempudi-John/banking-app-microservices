package com.example.profileservice.config;

import jakarta.servlet.DispatcherType;
import com.example.profileservice.security.InternalTokenFilter;
import com.example.profileservice.security.KycWebhookFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Configures request authorization, the servlet filter order, and JWT verification for this service.
 *
 * <p>Method security is enabled here, which is what makes {@code @PreAuthorize} on the controllers
 * take effect; without it those annotations are parsed and ignored, and every guarded endpoint
 * silently becomes reachable by any authenticated caller.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final KycWebhookFilter kycWebhookFilter;
    private final InternalTokenFilter internalTokenFilter;

    @Value("${application.security.jwt.secret-key:404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}")
    private String secretKey;

    public SecurityConfig(KycWebhookFilter kycWebhookFilter, InternalTokenFilter internalTokenFilter) {
        this.kycWebhookFilter = kycWebhookFilter;
        this.internalTokenFilter = internalTokenFilter;
    }

    /**
     * Builds the stateless filter chain: two shared-secret filters ahead of authentication, then
     * bearer-token resource-server validation.
     *
     * <p>{@code KycWebhookFilter} and {@code InternalTokenFilter} both run before
     * {@code UsernamePasswordAuthenticationFilter} because neither of their callers authenticates —
     * the vendor webhook proves itself with an HMAC signature, sibling services with a shared token.
     * Their order relative to each other is free: they guard the disjoint prefixes
     * {@code /api/v1/webhooks/} and {@code /api/v1/internal/} and each passes straight through on any
     * other path, so no request is ever inspected by both.
     *
     * <p>Those two prefixes are {@code permitAll} at the Spring Security layer precisely because the
     * filters, not this chain, are what gate them. Raising {@code /api/v1/internal/**} to
     * {@code authenticated()} breaks every caller: these are service-to-service calls with no
     * end-user token to present — notification-service sweeping daily-summary opt-ins has no user in
     * the room at all.
     *
     * <p>{@code /api/v1/internal/} is also the one prefix the k8s ingress does not route, so any new
     * unauthenticated endpoint must live under it. A {@code permitAll} path under a routed prefix
     * such as {@code /api/v1/profiles} is published to the internet.
     *
     * <p>The {@code ERROR} dispatch type is permitted so Spring's internal re-dispatch to
     * {@code /error} can render a body. Without it that dispatch is authorized as a fresh request and
     * every 400 or 404 reaches the caller as a bodyless 401 with the real reason lost. Matching the
     * dispatch type rather than the path keeps {@code /error} itself unreachable from outside.
     *
     * <p>Resource-server JWT validation uses {@link #jwtDecoder()} and maps the token's
     * {@code scope} claim to authorities ({@code FULL_AUTH} becomes {@code SCOPE_FULL_AUTH}), which
     * is what {@code PreferenceController}'s class-level {@code @PreAuthorize} check tests.
     *
     * @param http the builder Spring supplies; CSRF is disabled because no session cookie is issued
     * @return the built chain, registered as the single chain for this service
     * @throws Exception propagated from {@code HttpSecurity.build()} when the chain is misconfigured
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/v1/webhooks/**").permitAll()
                .requestMatchers("/api/v1/internal/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .addFilterBefore(kycWebhookFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(internalTokenFilter, UsernamePasswordAuthenticationFilter.class)
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }

    /**
     * Supplies the HS256 decoder that verifies bearer tokens locally.
     *
     * <p>The key is the same base64-encoded HMAC secret auth-service signs with, injected from
     * {@code application.security.jwt.secret-key} and supplied in production by the
     * {@code JWT_SECRET_KEY} k8s secret. Sharing the secret is what lets this service verify a
     * signature on its own rather than calling back to auth-service on every request; the flip side
     * is that the two services must be rotated together, and a token signed with a rotated key fails
     * verification here rather than being refreshed.
     *
     * @return a decoder accepting only {@code HS256}, so a token signed with any other algorithm is
     *     rejected rather than trusted
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        byte[] keyBytes = Base64.getDecoder().decode(secretKey);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Allows the Angular dev server to call this API cross-origin with credentials.
     *
     * <p>Credentialed requests are permitted so the {@code Authorization} header survives the
     * preflight, which forbids a wildcard origin — the allowed origin list must name each origin
     * explicitly, so a deployed frontend origin has to be added here before it can call this service.
     *
     * @return a source applying one policy to {@code /**}
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:4200"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}