package com.example.authservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.DispatcherType;

import java.util.List;

import com.example.authservice.security.InternalTokenFilter;
import com.example.authservice.security.JwtAuthenticationFilter;

/**
 * Defines the HTTP security rules for the auth service: which endpoints are reachable without a
 * token, and in what order the custom filters run.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final InternalTokenFilter internalTokenFilter;
    private final AuthenticationProvider authenticationProvider;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter,
                          InternalTokenFilter internalTokenFilter,
                          AuthenticationProvider authenticationProvider) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.internalTokenFilter = internalTokenFilter;
        this.authenticationProvider = authenticationProvider;
    }

    /**
     * Builds the single filter chain that authorizes every request this service serves.
     *
     * <p>Everything is denied by default; the {@code permitAll} entries are the deliberate holes.
     * {@code /login}, {@code /register}, {@code /verify-2fa/**}, and {@code /refresh} must be open
     * because a caller has no access token yet at those points, and {@code /logout} is
     * authenticated because it needs the token's {@code jti} to blacklist. The {@code ERROR}
     * dispatch is permitted separately: Spring re-dispatches internally to {@code /error} to render
     * an error body, and without that rule the re-dispatch is authorized as a fresh anonymous
     * request, turning every 400 and 404 into a bodyless 401 with the real reason lost. Matching on
     * the dispatch type rather than the path keeps {@code /error} itself unreachable from outside.
     *
     * <p>{@code /api/v1/internal/**} is permitted at this layer but is not unauthenticated:
     * {@code InternalTokenFilter} is its gate. Those routes are service-to-service only (for
     * example transaction-service resolving a transfer recipient's display name) and are not
     * published through the ingress, so no end-user token exists for them. Marking them
     * {@code authenticated()} would demand a user JWT the calling service cannot produce and would
     * break every internal call rather than protect it.
     *
     * <p>Filter order is load-bearing. {@code JwtAuthenticationFilter} runs before
     * {@code UsernamePasswordAuthenticationFilter} so a bearer token populates the security context
     * before form login would look at the request. {@code InternalTokenFilter} is then anchored
     * ahead of the JWT filter so an unauthorized internal call is rejected before any other work
     * happens; anchoring it to the JWT filter rather than to
     * {@code UsernamePasswordAuthenticationFilter} avoids the two landing on the same order value,
     * where the winner would come down to list order. That registration must stay after the JWT
     * filter's, because a filter can only be positioned relative to one already in the chain. The
     * internal filter ignores every path outside its prefix, so customer requests reach the JWT
     * filter unchanged.
     *
     * <p>Sessions are stateless: no {@code HttpSession} is created or read, so every request must
     * carry its own proof of identity and nothing survives on the server between calls. CSRF is
     * disabled on the same reasoning — the attack depends on a browser attaching an ambient session
     * cookie, and authorization here comes from an explicit bearer header.
     *
     * @param http the builder for this chain; each rule is applied in the order written above
     * @return the built chain, never {@code null}
     * @throws Exception if a rule cannot be applied, which fails startup rather than serving an
     *     unsecured application
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())

            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/auth/register").permitAll()
                .requestMatchers("/api/v1/auth/verify-2fa/**").permitAll()
                .requestMatchers("/api/v1/auth/refresh").permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .requestMatchers("/api/v1/internal/**").permitAll()
                .requestMatchers("/api/v1/auth/logout").authenticated()

                .anyRequest().authenticated()
            )

            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            .authenticationProvider(authenticationProvider)

            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

            .addFilterBefore(internalTokenFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Allows the Angular front end to call this API cross-origin with credentials attached.
     *
     * <p>Credentials are enabled because the device-recognition and refresh-token cookies are
     * {@code HttpOnly} and the browser will not send them on a cross-origin request otherwise. That
     * is also why the origin is listed explicitly instead of as {@code *}: browsers reject a
     * wildcard origin whenever credentials are allowed. Only the local dev origin is listed, so a
     * deployed front end must be added here or its requests are blocked by the browser before
     * reaching any filter.
     *
     * @return a source applying the same policy to every path, never {@code null}
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