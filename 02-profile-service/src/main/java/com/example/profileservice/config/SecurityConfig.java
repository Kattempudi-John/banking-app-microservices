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

// @EnableMethodSecurity is what actually makes @PreAuthorize on the controllers take effect,
// without it those annotations would just sit there completely ignored
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final KycWebhookFilter kycWebhookFilter;
    private final InternalTokenFilter internalTokenFilter;

    // Same base64-encoded HMAC secret auth-service signs tokens with (shared via the
    // JWT_SECRET_KEY k8s secret in prod, see application-prod.yml) so this service can verify
    // a token's signature on its own, without ever calling back to auth-service.
    @Value("${application.security.jwt.secret-key:404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}")
    private String secretKey;

    public SecurityConfig(KycWebhookFilter kycWebhookFilter, InternalTokenFilter internalTokenFilter) {
        this.kycWebhookFilter = kycWebhookFilter;
        this.internalTokenFilter = internalTokenFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            // Allow the Angular dev server (and later, its deployed origin) to call this API
            // cross-origin, including sending the Authorization header on credentialed requests.
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // webhooks and the kyc-status lookup stay open at the spring security layer since
            // they are protected by other means instead, the webhook by its own hmac signature
            // filter below, and kyc-status because it is meant for internal service to service calls
            .authorizeHttpRequests(auth -> auth
                // Spring re-dispatches internally to /error to render an error body. Without this,
                // that dispatch is authorized as if it were a fresh request, so every 400/404 comes
                // back as a bodyless 401 and the real reason never reaches the caller. Matching on
                // the ERROR dispatch type keeps /error itself from being publicly reachable.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/v1/webhooks/**").permitAll()
                // Service-to-service only, and the ONE prefix the k8s ingress does not route.
                // Anything unauthenticated has to live here: the ingress matches by path prefix, so a
                // permitAll endpoint under /api/v1/profiles (which is routed) is published to the
                // internet - exactly what the old /api/v1/profiles/*/kyc-status rule did.
                //
                // Still permitAll, and it has to stay that way: InternalTokenFilter is what gates
                // this prefix now. Tightening the rule to .authenticated() instead would demand a JWT
                // from callers who have no end-user token to send - notification-service sweeping
                // daily-summary opt-ins has no user in the room at all - and would break every one
                // of these calls.
                .requestMatchers("/api/v1/internal/**").permitAll()
                // Swagger/OpenAPI UI - documentation, not application data
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .addFilterBefore(kycWebhookFilter, UsernamePasswordAuthenticationFilter.class)
            // Both secret-checking filters sit ahead of authentication because neither of their
            // callers authenticates: the vendor's webhook proves itself with an HMAC, our own
            // services with a shared token. Their order relative to each other does not matter -
            // they guard disjoint prefixes (/api/v1/webhooks/ vs /api/v1/internal/) and each one
            // passes straight through on any path that is not its own, so no request is ever seen
            // by both as something to check.
            .addFilterBefore(internalTokenFilter, UsernamePasswordAuthenticationFilter.class)
            // Validates the bearer token against the JwtDecoder bean below and populates the
            // SecurityContext with a JwtAuthenticationToken, whose authorities come from the
            // token's "scope" claim (e.g. "FULL_AUTH" -> SCOPE_FULL_AUTH) — needed for
            // PreferenceController's class-level @PreAuthorize check to ever pass.
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        byte[] keyBytes = Base64.getDecoder().decode(secretKey);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

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