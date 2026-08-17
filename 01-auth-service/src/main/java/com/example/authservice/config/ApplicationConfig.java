package com.example.authservice.config;

import com.example.authservice.service.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Supplies the password-hashing and authentication beans the security filter chain builds on.
 *
 * <p>Each bean below is the only definition of its type in the service, so {@code SecurityConfig}
 * and every registration path resolve to these instances by type alone.
 */
@Configuration
public class ApplicationConfig {

    /**
     * Defines the single hashing scheme used to both store and verify passwords.
     *
     * <p>Registration hashes through this bean and {@link #authenticationProvider} verifies through
     * the same one; they must stay the same instance or logins fail against passwords that were
     * stored correctly. BCrypt embeds its cost factor and salt in the hash string itself, so the
     * strength can be raised later without a migration, but switching to a different algorithm
     * invalidates every hash already in the {@code users} table.
     *
     * @return the shared encoder, never {@code null}
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Wires username lookup to password verification for the username/password login flow.
     *
     * <p>This is the provider {@code SecurityConfig} registers on the filter chain, which is what
     * puts it behind the {@code AuthenticationManager} below. It reports a missing user and a wrong
     * password identically, as {@code BadCredentialsException}, so a caller cannot probe for which
     * usernames exist.
     *
     * @param userDetailsService must load by the same username value the login request carries;
     *     a {@code UsernameNotFoundException} from it surfaces as bad credentials, not a 404
     * @param passwordEncoder must be the encoder the stored hashes were produced with
     * @return the provider, never {@code null}
     */
    @Bean
    public AuthenticationProvider authenticationProvider(CustomUserDetailsService userDetailsService,
                                                           PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    /**
     * Exposes Spring Security's own authentication manager so services can trigger a login
     * explicitly.
     *
     * <p>Taken from {@code AuthenticationConfiguration} rather than constructed here on purpose:
     * the container-built manager already has {@link #authenticationProvider} registered, whereas a
     * hand-built one would have no providers and reject every credential.
     *
     * @param config the container's own security configuration, injected; not user-supplied
     * @return the manager backing programmatic {@code authenticate} calls, never {@code null}
     * @throws Exception if the security configuration cannot be resolved, which aborts startup
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
