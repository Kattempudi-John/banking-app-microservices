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

/**
 * Defines the HTTP security posture for the two kinds of caller this service accepts: an end user
 * presenting a JWT on {@code /api/v1/notifications}, and another service presenting an internal
 * token on {@code /api/v1/internal/**}.
 *
 * <p>Everything else in this module is Kafka-driven and has no HTTP surface at all, so these are
 * the only two paths the rules below have to serve.
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
     * Builds the single filter chain, in which the ordering of three rules is what carries the
     * security, not the rules themselves.
     *
     * <p>The {@code ERROR} dispatch type is permitted because Spring re-dispatches internally to
     * {@code /error} to render an error body; without this, that dispatch is authorized as a fresh
     * request and every 400/404 comes back as a bodyless 401 with the real reason lost. Matching on
     * the dispatch type rather than the path keeps {@code /error} itself unreachable from outside.
     *
     * <p>{@code /api/v1/internal/**} stays {@code permitAll} here because service-to-service callers
     * carry no end-user token to present; {@code InternalTokenFilter} is the gate for that prefix,
     * not these rules. It is also the one prefix the k8s ingress does not route, which is why
     * anything unauthenticated must live under it — the ingress matches by path prefix, so a
     * {@code permitAll} endpoint under a routed prefix is published to the internet. That matters
     * because {@code InternalNotificationController} triggers real email sends.
     *
     * <p>{@code InternalTokenFilter} is placed ahead of {@code BearerTokenAuthenticationFilter} so
     * an internal request is rejected before any JWT parsing runs — internal callers have no
     * {@code Authorization} header to parse. Requests outside the internal prefix pass through the
     * filter untouched, so moving it after the bearer filter would only add JWT work to requests
     * that are about to be rejected.
     *
     * @param http never {@code null}; supplied by Spring and mutated in place
     * @return the built chain, with sessions stateless and CSRF off because every caller is a token
     *     bearer rather than a browser form post
     * @throws Exception if the chain cannot be assembled, which fails application startup
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/v1/internal/**").permitAll()
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .addFilterBefore(internalTokenFilter, BearerTokenAuthenticationFilter.class)
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }

    /**
     * Disables the servlet container's own registration of {@code InternalTokenFilter}.
     *
     * <p>The filter is a {@code @Component}, and Spring Boot auto-registers any {@code Filter} bean
     * it finds directly with the container. Left alone it would run twice per request: once
     * standalone, once inside the security chain. Turning the container registration off leaves the
     * security chain as the single place it runs, which is the only place its position relative to
     * the bearer-token filter means anything.
     *
     * @param filter the same singleton the security chain wires in, not a second instance
     * @return a registration deliberately left disabled; enabling it reintroduces the double
     *     invocation
     */
    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilterRegistration(
            InternalTokenFilter filter) {
        FilterRegistrationBean<InternalTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Builds the HS256 decoder that validates user tokens on {@code /api/v1/notifications}.
     *
     * <p>The key is the base64-encoded HMAC secret auth-service signs with, read from
     * {@code application.security.jwt.secret-key} and defaulted to the same development value every
     * other customer-facing service in this project uses. A deployment that overrides the secret in
     * auth-service but not here rejects every token as an invalid signature.
     *
     * @return a decoder pinned to {@code HS256}; a token signed with any other algorithm is
     *     rejected rather than trusted
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        byte[] keyBytes = Base64.getDecoder().decode(secretKey);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Allows browser calls from the local Angular development origin only.
     *
     * <p>The allowed origin is hard-coded to {@code http://localhost:4200} and credentials are
     * enabled, which forbids a wildcard origin — a deployed frontend on any other host needs this
     * list extended or its requests fail preflight.
     *
     * @return a source applied to every path, since the only browser-reachable path is the
     *     notifications feed
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
