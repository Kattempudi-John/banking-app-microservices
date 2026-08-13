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

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // Same base64-encoded HMAC secret auth-service signs tokens with (shared via the
    // JWT_SECRET_KEY k8s secret in prod, see application-prod.yml) so this service can verify
    // a token's signature on its own, without ever calling back to auth-service.
    @Value("${application.security.jwt.secret-key:404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}")
    private String secretKey;

    // Shared secret every service in this project presents on its /api/v1/internal/** calls, same
    // property name and same dev default in all five so docker-compose still starts with no config.
    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            // Allow the Angular dev server (and later, its deployed origin) to call this API
            // cross-origin, including sending the Authorization header on credentialed requests.
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                // When a handler throws, Spring re-dispatches the request internally to /error to
                // render the response body. That dispatch was being authorized like a fresh request,
                // so with nothing permitting it every 400/403/404 came back as a bodyless 401 instead
                // - including INSUFFICIENT_FUNDS and ownership failures, which transaction-service
                // relays to the user. Permitting the ERROR dispatch specifically (rather than the
                // "/error" path) keeps /error from being reachable as a public endpoint on its own.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // Internal, service-to-service only endpoints (transaction-service's transfer/
                // debit/credit calls, notification-service's balances-batch lookup) - not reachable
                // via the k8s ingress, so no end-user JWT is ever available to satisfy SCOPE_FULL_AUTH here.
                // Everything permitted here MUST live under this one prefix: the ingress routes by
                // path prefix, so an unauthenticated endpoint anywhere else (balances/batch used to be
                // under /api/v1/accounts) is an endpoint published straight to the internet.
                // Still permitAll, on purpose: InternalTokenFilter below is what gates these, and
                // requiring authentication here instead would reject every caller outright, since
                // the services calling in hold no user token to present.
                .requestMatchers("/api/v1/internal/**").permitAll()
                // Swagger/OpenAPI UI - documentation, not application data
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                // simplest possible rule set here, just one line, since @PreAuthorize on the
                // controller itself is what actually enforces the full auth scope requirement
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            // Validates the bearer token against the JwtDecoder bean below and populates the
            // SecurityContext with a JwtAuthenticationToken, whose authorities come from the
            // token's "scope" claim (e.g. "FULL_AUTH" -> SCOPE_FULL_AUTH) — this is what
            // @PreAuthorize("hasAuthority('SCOPE_FULL_AUTH')") on AccountController checks against.
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            // Placed before the JWT authentication filter so an internal request is rejected on its
            // shared secret before any token parsing happens - internal callers have no bearer token
            // to parse anyway. Constructed by hand rather than registered as a @Component: any Filter
            // that is also a bean gets auto-registered by Boot across the WHOLE servlet chain, so it
            // would then run twice per request, once inside this chain and once outside it.
            .addFilterBefore(new InternalTokenFilter(internalToken), BearerTokenAuthenticationFilter.class);

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