package com.example.notificationservice.config;

import com.example.notificationservice.security.InternalTokenFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;

// This service's first-ever SecurityConfig - until GET /api/v1/notifications existed, nothing ever
// called this service over HTTP with a user's JWT, only Kafka events and internal Feign calls
// this service itself makes outward.
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // Same base64-encoded HMAC secret auth-service signs tokens with, same default as every other
    // customer-facing service in this project (see account-service's SecurityConfig).
    @Value("${application.security.jwt.secret-key:404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}")
    private String secretKey;

    private final InternalTokenFilter internalTokenFilter;

    public SecurityConfig(InternalTokenFilter internalTokenFilter) {
        this.internalTokenFilter = internalTokenFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                // Spring re-dispatches internally to /error to render an error body. Without this,
                // that dispatch is authorized as if it were a fresh request, so every 400/404 comes
                // back as a bodyless 401 and the real reason never reaches the caller. Matching on
                // the ERROR dispatch type keeps /error itself from being publicly reachable.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // Operator/service-to-service only, and the ONE prefix the k8s ingress does not
                // route. Anything unauthenticated has to live here: the ingress matches by path
                // prefix, so a permitAll endpoint under a routed prefix is published to the
                // internet. That matters more than usual for InternalNotificationController, which
                // triggers real email sends. permitAll STAYS: InternalTokenFilter below is the gate
                // now, and demanding a JWT here instead would break every caller of these endpoints,
                // since a service-to-service call carries no end-user token to present.
                .requestMatchers("/api/v1/internal/**").permitAll()
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            // Ahead of the bearer-token filter so an internal request is rejected before any JWT
            // parsing happens - internal callers have no Authorization header to parse anyway, and
            // an unauthenticated request has no business getting further into the chain than it
            // must. Requests outside /api/v1/internal/ are passed straight through by the filter.
            .addFilterBefore(internalTokenFilter, BearerTokenAuthenticationFilter.class)
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }

    // InternalTokenFilter is a @Component, and Spring Boot auto-registers any Filter bean it finds
    // with the servlet container as well. Left alone, the filter would run twice on every request:
    // once standalone, once inside the security chain above. Disabling the container registration
    // leaves the security chain as the single place it runs, which is where the ordering above is
    // meaningful.
    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilterRegistration(
            InternalTokenFilter filter) {
        FilterRegistrationBean<InternalTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
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
