package com.example.authservice.config;
import com.example.authservice.security.InternalTokenFilter;
import com.example.authservice.security.JwtAuthenticationFilter;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Defines the HTTP security rules for the auth service.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final InternalTokenFilter internalTokenFilter;
    private final AuthenticationProvider authenticationProvider;

    /**
     * LOCAL/QA automation support.
     *
     * Production should explicitly set:
     *
     * automation.test-support.enabled=false
     */
    @Value("${automation.test-support.enabled:false}")
    private boolean automationTestSupportEnabled;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthFilter,
            InternalTokenFilter internalTokenFilter,
            AuthenticationProvider authenticationProvider
    ) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.internalTokenFilter = internalTokenFilter;
        this.authenticationProvider = authenticationProvider;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        http
                .csrf(csrf -> csrf.disable())

                .cors(cors ->
                        cors.configurationSource(corsConfigurationSource())
                )

                .authorizeHttpRequests(auth -> {

                    /*
                     * Allow Spring's internal ERROR dispatch so real
                     * 400/404 responses are not converted into 401 responses.
                     */
                    auth.dispatcherTypeMatchers(
                            DispatcherType.ERROR
                    ).permitAll();

                    /*
                     * Authentication bootstrap endpoints.
                     *
                     * Users do not yet have a FULL_AUTH access token
                     * when calling these endpoints.
                     */
                    auth.requestMatchers(
                            "/api/v1/auth/login"
                    ).permitAll();

                    auth.requestMatchers(
                            "/api/v1/auth/register"
                    ).permitAll();

                    auth.requestMatchers(
                            "/api/v1/auth/verify-2fa/**"
                    ).permitAll();

                    auth.requestMatchers(
                            "/api/v1/auth/refresh"
                    ).permitAll();

                    /*
                     * Swagger/OpenAPI.
                     */
                    auth.requestMatchers(
                            "/swagger-ui/**",
                            "/swagger-ui.html",
                            "/v3/api-docs/**"
                    ).permitAll();

                    /*
                     * Internal service endpoints.
                     *
                     * These are protected by InternalTokenFilter,
                     * not by an end-user JWT.
                     */
                    auth.requestMatchers(
                            "/api/v1/internal/**"
                    ).permitAll();

                    /*
                     * LOCAL / QA AUTOMATION ONLY.
                     *
                     * This rule is added only when:
                     *
                     * automation.test-support.enabled=true
                     *
                     * The controller itself is also conditionally created,
                     * giving us two layers of protection.
                     */
                    if (automationTestSupportEnabled) {
                        auth.requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/test-support/otp"
                        ).permitAll();
                    }

                    /*
                     * Logout needs an authenticated access token because
                     * its JTI is blacklisted.
                     */
                    auth.requestMatchers(
                            "/api/v1/auth/logout"
                    ).authenticated();

                    /*
                     * Everything not explicitly listed above requires
                     * authentication.
                     */
                    auth.anyRequest().authenticated();
                })

                /*
                 * JWT authentication is stateless.
                 */
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .authenticationProvider(authenticationProvider)

                /*
                 * JWT authentication runs before Spring's normal
                 * username/password authentication filter.
                 */
                .addFilterBefore(
                        jwtAuthFilter,
                        UsernamePasswordAuthenticationFilter.class
                )

                /*
                 * Internal service authentication runs before JWT
                 * authentication.
                 */
                .addFilterBefore(
                        internalTokenFilter,
                        JwtAuthenticationFilter.class
                );

        return http.build();
    }

    /**
     * CORS configuration for the local Angular application.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration =
                new CorsConfiguration();

        configuration.setAllowedOrigins(
                List.of("http://localhost:4200")
        );

        configuration.setAllowedMethods(
                List.of(
                        "GET",
                        "POST",
                        "PUT",
                        "PATCH",
                        "DELETE",
                        "OPTIONS"
                )
        );

        configuration.setAllowedHeaders(
                List.of(
                        "Authorization",
                        "Content-Type"
                )
        );

        /*
         * Required because Device-ID and refresh-token cookies
         * are HttpOnly browser cookies.
         */
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/**",
                configuration
        );

        return source;
    }
}