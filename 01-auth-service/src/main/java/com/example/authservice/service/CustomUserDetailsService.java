package com.example.authservice.service;

import com.example.authservice.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Bridges Spring Security's user lookup to the {@code users} table.
 *
 * <p>Registering this as the sole {@code UserDetailsService} bean is what makes the rest of the
 * service's principals be the application's own {@code User} entity rather than Spring's
 * built-in one, which every {@code (User) authentication.getPrincipal()} cast in the controllers
 * and filters depends on.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Loads a user by login name, narrowing the interface's return type to this service's own
     * {@code User} entity.
     *
     * <p>Callers may rely on that: the returned value is always castable to {@code User}, which
     * is how the numeric id reaches token generation and how the controllers read the principal.
     *
     * @param username matched exactly, case-sensitively, as stored at registration
     * @return the persisted user, never {@code null}
     * @throws UsernameNotFoundException when no such user exists; the authentication provider
     *     converts this into the same failure a wrong password produces, so the two are
     *     indistinguishable to a caller probing for valid usernames
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No user found with username: " + username));
    }
}
