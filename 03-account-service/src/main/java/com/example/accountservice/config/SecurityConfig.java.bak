package com.example.accountservice.config;

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

import com.example.accountservice.security.InternalTokenFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.DispatcherType;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Wires the HTTP security chain, the JWT verifier, and the browser CORS policy for this service.
 *
 * <p>{@link InternalTokenFilter} is constructed by hand inside the chain rather than declared as a
 * {@code @Component}. Any {@code Filter} that is also a bean is auto-registered by Boot across the
 * <em>whole</em> servlet chain, so it would run twice per request — once inside this chain and once
 * outside it. Do not "simplify" it into a component.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${application.security.jwt.secret-key:404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}")
    private String secretKey;

    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    /**
     * Builds the stateless filter chain: internal shared-secret check first, bearer token second.
     *
     * <p>{@code InternalTokenFilter} is placed <em>before</em> {@code BearerTokenAuthenticationFilter}
     * so a service-to-service request is settled on its shared secret before any token parsing runs;
     * those callers hold no user token to parse. Reversing the order rejects every internal caller.
     *
     * <p>Everything under {@code /api/v1/internal/**} is {@code permitAll} at the authorize layer on
     * purpose — the filter is what gates it, and requiring authentication here would reject callers
     * that have no user JWT to present. The corollary is a hard rule: every unauthenticated endpoint
     * MUST live under that one prefix, because the k8s ingress refuses to route it by path prefix. An
     * endpoint permitted anywhere else is published straight to the internet.
     *
     * <p>The {@code ERROR} dispatcher type is permitted so that Spring's internal re-dispatch to
     * {@code /error} can render a response body. Without it every 400/403/404 — including
     * {@code INSUFFICIENT_FUNDS} and ownership failures that transaction-service relays to the user —
     * came back as a bodyless 401. Permitting the dispatch type rather than the {@code "/error"} path
     * keeps {@code /error} from being reachable as a public endpoint of its own.
     *
     * <p>Authorization rules stop at {@code anyRequest().authenticated()} deliberately;
     * {@code @PreAuthorize("hasAuthority('SCOPE_FULL_AUTH')")} on the controllers is what enforces
     * the scope requirement, and duplicating it here would mean two places to keep in sync.
     *
     * @param http never {@code null}; supplied by Spring Security's builder
     * @return the built chain
     * @throws Exception when the underlying builder fails to assemble the chain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/v1/internal/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            .addFilterBefore(new InternalTokenFilter(internalToken), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Builds the HS256 verifier the resource server authenticates bearer tokens with.
     *
     * <p>The key is the same base64-encoded HMAC secret auth-service signs with, shared in
     * production through the {@code JWT_SECRET_KEY} k8s secret. Symmetric signing is what lets this
     * service verify a token entirely on its own, with no callback to auth-service on the hot path;
     * the flip side is that rotating the secret must happen in both services together.
     *
     * <p>Authorities on the resulting authentication come from the token's {@code scope} claim
     * ({@code "FULL_AUTH"} becomes {@code SCOPE_FULL_AUTH}), which is what the controllers'
     * {@code @PreAuthorize} expressions match on.
     *
     * @return a decoder pinned to {@code HS256}; a token signed with any other algorithm is rejected
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        byte[] keyBytes = Base64.getDecoder().decode(secretKey);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Defines the browser CORS policy for the customer-facing API.
     *
     * <p>Origins are an explicit allow-list rather than a wildcard because credentials are enabled,
     * and the two cannot be combined: a wildcard origin makes the browser drop the response. A new
     * deployed frontend origin has to be added here or its calls fail preflight.
     *
     * @return a source applying the same policy to every path
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