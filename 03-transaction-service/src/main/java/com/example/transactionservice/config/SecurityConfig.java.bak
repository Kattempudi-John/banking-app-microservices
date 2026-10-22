package com.example.transactionservice.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.example.transactionservice.security.InternalTokenFilter;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Defines the HTTP security chain for the transaction service.
 *
 * <p>The service is a stateless OAuth2 resource server: it verifies the caller's bearer token
 * locally against a shared HMAC secret and never calls back to auth-service to do so.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${application.security.jwt.secret-key:404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}")
    private String secretKey;

    private final InternalTokenFilter internalTokenFilter;

    public SecurityConfig(InternalTokenFilter internalTokenFilter) {
        this.internalTokenFilter = internalTokenFilter;
    }

    /**
     * Builds the single filter chain: stateless JWT for customer paths, shared secret for internal
     * ones.
     *
     * <p>{@code /api/v1/internal/**} is {@code permitAll} at the authorization layer and gated by
     * {@link InternalTokenFilter} instead. Those endpoints are called service-to-service, so a
     * caller has no end-user JWT to present; leaving them under {@code anyRequest().authenticated()}
     * would only admit whichever callers happened to carry a user token, which is not a check on the
     * calling service's identity at all.
     *
     * <p>{@code InternalTokenFilter} is anchored before {@link BearerTokenAuthenticationFilter} so
     * the shared secret is checked before anything else on the request is treated as a credential.
     * That anchor filter is registered by the {@code oauth2ResourceServer} call, so the
     * {@code addFilterBefore} must stay after it — {@code addFilterBefore} can only anchor to a
     * filter already in the chain, and reordering the two lines throws at startup. The filter
     * ignores every path outside the internal prefix, so customer-facing requests still reach the
     * bearer-token filter unchanged.
     *
     * <p>The {@code ERROR} dispatch type is permitted because Spring re-dispatches internally to
     * {@code /error} to render an error body; without it that dispatch is authorized as a fresh
     * request and anything {@code GlobalExceptionHandler} does not catch — bean-validation failures,
     * 404s — reaches the user as a bodyless 401 with the real reason discarded. Matching the
     * dispatch type rather than the path keeps {@code /error} itself unreachable from outside.
     *
     * <p>Customer endpoints are additionally narrowed by method-level {@code @PreAuthorize} on
     * {@code TransferController}, which requires {@code SCOPE_FULL_AUTH}; authorities are derived
     * from the token's {@code scope} claim.
     *
     * @param http the chain under construction; CSRF is disabled because no session cookie is issued
     * @return the built chain, never {@code null}
     * @throws Exception when the chain cannot be assembled, which fails application startup
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .requestMatchers("/api/v1/internal/**").permitAll()
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))

            .addFilterBefore(internalTokenFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Builds the decoder that verifies inbound bearer tokens.
     *
     * <p>Keyed on the same base64-encoded HMAC secret auth-service signs with, supplied in
     * production through the {@code JWT_SECRET_KEY} secret. Verification is therefore local: this
     * service never calls auth-service to validate a token, and a secret that drifts out of step
     * with auth-service's rejects every token rather than degrading gracefully.
     *
     * @return an HS256 decoder, never {@code null}
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        byte[] keyBytes = Base64.getDecoder().decode(secretKey);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Builds the CORS policy for the browser client.
     *
     * <p>Restricted to the Angular dev server origin, with credentials allowed so the
     * {@code Authorization} header survives the preflight. A deployed frontend origin has to be
     * added here or its calls fail preflight rather than returning a 401.
     *
     * @return a source mapping the policy to every path, never {@code null}
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
