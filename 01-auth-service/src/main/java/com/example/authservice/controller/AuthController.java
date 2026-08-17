package com.example.authservice.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.authservice.model.RefreshToken;
import com.example.authservice.model.User;
import com.example.authservice.repository.RefreshTokenRepository;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.security.TokenType;
import com.example.authservice.service.AuthSecurityService;
import com.example.authservice.service.AuthSecurityService.TwoFaResult;
import com.example.authservice.service.JwtService;
import com.example.authservice.util.PhoneNumberNormalizer;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Exposes the credential lifecycle for end users: registration, the two-phase login, 2FA
 * verification, sliding-session refresh, and logout.
 *
 * <p>Login is deliberately split in two. A password check alone never yields a usable token
 * unless the caller presents a {@code Device-ID} cookie this service has seen before; otherwise
 * it yields a {@code PRE_AUTH} token that {@code JwtAuthenticationFilter} refuses to accept
 * anywhere except the {@code /verify-2fa} paths below. The class-level {@code /api/v1/auth}
 * prefix is therefore part of the security model, not just tidiness: the filter matches on the
 * request URI, so relocating these mappings changes which tokens can reach them.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AuthSecurityService authSecurityService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PhoneNumberNormalizer phoneNumberNormalizer;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtService jwtService,
                          AuthSecurityService authSecurityService,
                          RefreshTokenRepository refreshTokenRepository,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          PhoneNumberNormalizer phoneNumberNormalizer) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.authSecurityService = authSecurityService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.phoneNumberNormalizer = phoneNumberNormalizer;
    }

    private static final int MIN_PASSWORD_LENGTH = 8;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /**
     * Creates a user account and announces it to the rest of the platform.
     *
     * <p>An email address is mandatory here even though the column is nullable; the column only
     * permits {@code null} for accounts that predate it. The format check is deliberately loose,
     * enough to catch a missing {@code @} and no more, because real deliverability is the mail
     * provider's answer to give, not a regex's.
     *
     * <p>A phone number is optional, but one that cannot be normalized to E.164 is rejected
     * rather than stored as typed: the number travels on the 2FA event as the fallback contact
     * and the SMS provider refuses any other shape. Uniqueness is checked on the normalized
     * value, so the same phone typed two ways is still one phone, and it is enforced for the
     * same reason as email, since whoever holds the number receives that account's 2FA codes.
     *
     * <p>On success a {@code user-events} message is published; profile-service and
     * account-service provision their own rows from it, so this is the only way they learn the
     * user exists.
     *
     * @param request must carry non-blank {@code username}, a {@code password} of at least 8
     *     characters, and a syntactically valid {@code email};
     *     {@code phoneNumber} is optional but must normalize to E.164 when supplied
     * @return {@code 201} on success, {@code 400} for a missing or malformed field, {@code 409}
     *     when the username, email, or phone number already belongs to another account
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");
        String phoneNumber = request.get("phoneNumber");
        String email = request.get("email");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password are required"));
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
        }
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please enter a valid email address"));
        }
        if (userRepository.existsByUsername(username)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Username is already taken"));
        }
        if (userRepository.existsByEmail(email)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "That email is already registered"));
        }

        String normalizedPhone = null;
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            normalizedPhone = phoneNumberNormalizer.normalize(phoneNumber)
                    .orElse(null);
            if (normalizedPhone == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Please enter a valid phone number, e.g. 571-285-6947 or +15712856947"));
            }
            if (userRepository.existsByPhoneNumber(normalizedPhone)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "That phone number is already registered"));
            }
        }

        User newUser = new User();
        newUser.setUsername(username);
        newUser.setPassword(passwordEncoder.encode(password));
        newUser.setPhoneNumber(normalizedPhone);
        newUser.setEmail(email);
        newUser.setTotpEnabled(false);
        userRepository.save(newUser);
        authSecurityService.publishUserRegisteredEvent(
                newUser.getId(), newUser.getUsername(), newUser.getPhoneNumber(), newUser.getEmail());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("status", "SUCCESS", "message", "Account created successfully"));
    }

    /**
     * Verifies a username and password, then either completes the login or opens a 2FA challenge.
     *
     * <p>Which of the two happens is decided by the device cookie, not by the request body, so a
     * caller must branch on the {@code status} field of the response: a device hash already on
     * file for this user skips 2FA and returns a 15-minute {@code FULL_AUTH} token, while
     * anything else returns {@code 202} and a 5-minute {@code PRE_AUTH} token that is only good
     * against the {@code /verify-2fa} endpoints.
     *
     * @param request must carry {@code username} and {@code password}
     * @param deviceCookie the raw {@code Device-ID} cookie issued by an earlier successful 2FA;
     *     {@code null} on a first login and on any browser that dropped it, which is not an
     *     error and simply forces the challenge
     * @return {@code 200} with {@code access_token} plus a refreshed {@code Refresh-Token}
     *     cookie for a recognized device, or {@code 202} with {@code pre_auth_token} and
     *     {@code expires_in_seconds} when 2FA is required
     * @throws org.springframework.security.core.AuthenticationException when the username is
     *     unknown or the password does not match; the two are reported identically so a caller
     *     cannot probe for which usernames exist
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request,
                                   @CookieValue(name = "Device-ID", required = false) String deviceCookie) {

        String username = request.get("username");
        String password = request.get("password");

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password)
        );
        User user = (User) authentication.getPrincipal();

        boolean isRecognized = authSecurityService.isDeviceRecognized(user.getId(), deviceCookie);

        if (isRecognized) {
            return handleRecognizedDeviceLogin(user);
        } else {
            return handleUnrecognizedDeviceLogin(user);
        }
    }

    private ResponseEntity<?> handleRecognizedDeviceLogin(User user) {
        String fullJwt = jwtService.generateToken(user, TokenType.FULL_AUTH);
        ResponseCookie refreshCookie = createRefreshTokenCookie(user.getId());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(Map.of("status", "SUCCESS", "access_token", fullJwt));
    }

    private ResponseEntity<?> handleUnrecognizedDeviceLogin(User user) {
        return issue2faChallenge(user);
    }

    private ResponseEntity<?> issue2faChallenge(User user) {
        String preAuthJwt = jwtService.generateToken(user, TokenType.PRE_AUTH);
        int expiresInSeconds = authSecurityService.trigger2fa(user.getId(), user.getPhoneNumber(), user.getEmail());

        return ResponseEntity.accepted().body(Map.of(
                "status", "2FA_REQUIRED",
                "pre_auth_token", preAuthJwt,
                "expires_in_seconds", expiresInSeconds));
    }

    private static final String NOT_AUTHENTICATED_MESSAGE = "Not authenticated. Please log in again.";

    /**
     * Verifies a 2FA code and upgrades the caller's half-authenticated session to a full one.
     *
     * <p>The caller is identified from the {@code PRE_AUTH} token alone; nothing in the body
     * names a user. That token reaches this method only because the URI sits under
     * {@code /verify-2fa}, which is the sole prefix {@code JwtAuthenticationFilter} admits a
     * {@code PRE_AUTH} token on. The endpoint is also {@code permitAll}, since by definition the
     * caller has no full token yet, so a request carrying no token at all still arrives here and
     * is answered {@code 401} rather than being turned away by the filter chain.
     *
     * <p>Each failure gets its own status and a machine-readable {@code reason}, because the
     * client has to act differently on each: a wrong digit means try again, a missing or expired
     * code means press resend, and a lockout means start the login over. {@code EXPIRED} answers
     * {@code 410} rather than {@code 401} because the credential was real and merely no longer
     * exists, and the UI keys its "ask for another" state off that status.
     *
     * <p>A successful call registers this device, so the response sets a year-long
     * {@code Device-ID} cookie alongside a fresh {@code Refresh-Token}; a later login presenting
     * that cookie skips 2FA entirely.
     *
     * @param request must carry {@code code}, the six digits from the delivered message; a
     *     missing or non-matching value is a normal {@code 401}, not a validation error
     * @return {@code 200} with {@code access_token} on success, {@code 401} for a wrong or
     *     absent code, {@code 410} once the code has expired, {@code 429} after three failed
     *     attempts have burned the code
     */
    @PostMapping("/verify-2fa/sms")
    public ResponseEntity<?> verifySms(@RequestBody Map<String, String> request) {

        User user = currentPreAuthUser();
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", NOT_AUTHENTICATED_MESSAGE));
        }
        String code = request.get("code");

        TwoFaResult result = authSecurityService.verifySms2fa(user.getId(), code);

        return switch (result) {
            case VALID -> buildSuccessfulAuthResponse(user);
            case INVALID -> ResponseEntity.status(401)
                    .body(Map.of("error", "Invalid 2FA code", "reason", "INVALID"));
            case NO_CODE -> ResponseEntity.status(401)
                    .body(Map.of("error", "No active code. Request a new one.", "reason", "NO_CODE"));
            case EXPIRED -> ResponseEntity.status(410)
                    .body(Map.of("error", "Your code has expired. Request a new one.", "reason", "EXPIRED"));
            case LOCKED -> ResponseEntity.status(429)
                    .body(Map.of("error", "Too many failed attempts. Please log in again.", "reason", "LOCKED"));
        };
    }

    /**
     * Issues a replacement 2FA code and a replacement {@code PRE_AUTH} token for the same session.
     *
     * <p>Separate from {@code /login} rather than a flag on it, because the caller has already
     * passed the password check and re-posting credentials for a second code would mean the
     * frontend holding the password for the length of the 2FA screen.
     *
     * <p>The path is load-bearing. {@code JwtAuthenticationFilter} admits a {@code PRE_AUTH}
     * token only on URIs containing {@code /api/v1/auth/verify-2fa}; mapped anywhere else this
     * endpoint would answer {@code 403} to the only token a caller at this stage can hold.
     *
     * <p>Callers must swap in the returned {@code pre_auth_token} and discard the one they hold.
     * The original expires 5 minutes after login, so a user who resends twice would otherwise
     * end up with a code that works and a session token that has already died.
     *
     * @return {@code 202} with a new {@code pre_auth_token} and {@code expires_in_seconds};
     *     {@code 429} with {@code retry_after_seconds} while the resend cooldown is still
     *     running, in which case no code is sent and the previous one stays valid; {@code 401}
     *     when no {@code PRE_AUTH} token was presented
     */
    @PostMapping("/verify-2fa/resend")
    public ResponseEntity<?> resend2fa() {
        User user = currentPreAuthUser();
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", NOT_AUTHENTICATED_MESSAGE));
        }

        int retryAfterSeconds = authSecurityService.secondsUntilResendAllowed(user.getId());
        if (retryAfterSeconds > 0) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "Please wait before requesting another code.",
                    "retry_after_seconds", retryAfterSeconds));
        }

        return issue2faChallenge(user);
    }

    private User currentPreAuthUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        if (!(auth.getPrincipal() instanceof User user)) {
            return null;
        }
        return user;
    }

    private ResponseEntity<?> buildSuccessfulAuthResponse(User user) {
        String fullJwt = jwtService.generateToken(user, TokenType.FULL_AUTH);
        String rawDeviceId = authSecurityService.registerNewDevice(user.getId());

        ResponseCookie deviceCookie = ResponseCookie.from("Device-ID", rawDeviceId)
                .httpOnly(true).secure(true).path("/").maxAge(31536000)
                .build();

        ResponseCookie refreshCookie = createRefreshTokenCookie(user.getId());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, deviceCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(Map.of("status", "SUCCESS", "access_token", fullJwt));
    }

    /**
     * Exchanges a refresh-token cookie for a fresh 15-minute access token.
     *
     * <p>The cookie holds the raw token and the database holds only its SHA-256 hash, so this is
     * a lookup by hash rather than a comparison. Nothing is rotated: the returned access token
     * is new, but the refresh cookie is untouched and its 24-hour window still runs from when it
     * was first issued, so a client cannot extend a session indefinitely by refreshing.
     *
     * <p>A cookie that was revoked by {@code /logout} or has aged out is answered {@code 401},
     * which is the client's cue to send the user back through the full login.
     *
     * @param refreshToken the raw {@code Refresh-Token} cookie; declared optional so a missing
     *     cookie arrives as {@code null} and is answered {@code 401} instead of Spring's
     *     automatic {@code 400}
     * @return {@code 200} carrying a new {@code access_token}, or {@code 401} when the cookie is
     *     absent, revoked, or past its expiry
     * @throws RuntimeException when the presented cookie matches no stored token at all, which
     *     surfaces as a {@code 500} rather than a {@code 401}
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshSession(@CookieValue(name = "Refresh-Token", required = false) String refreshToken) {
        if (refreshToken == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Refresh token missing"));
        }

        RefreshToken storedToken = validateAndFetchRefreshToken(refreshToken);
        if (storedToken == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Refresh token expired or revoked"));
        }

        User user = getUserById(storedToken.getUserId());
        String newJwt = jwtService.generateToken(user, TokenType.FULL_AUTH);

        return ResponseEntity.ok(Map.of("access_token", newJwt));
    }

    private RefreshToken validateAndFetchRefreshToken(String refreshToken) {
        String hashedToken = hashString(refreshToken);
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(hashedToken)
                .orElseThrow(() -> new RuntimeException("Invalid refresh token"));

        if (!storedToken.isValid()) {
            return null;
        }
        return storedToken;
    }

    /**
     * Ends the caller's session by blacklisting the presented access token and revoking every
     * refresh token the user holds.
     *
     * <p>The access token's {@code jti} is blacklisted until its own expiry, which is what stops
     * it being replayed for the rest of its 15 minutes; {@code JwtAuthenticationFilter} consults
     * that list on every request. Revocation covers all of the user's refresh tokens, not only
     * the one on this request, so logging out here signs the account out everywhere.
     *
     * <p>The {@code Device-ID} cookie is deliberately left alone. Logging out is not the same as
     * distrusting the machine, so the next login from this browser still skips 2FA.
     *
     * @param request only the {@code Authorization} header is read; a missing or
     *     non-{@code Bearer} header skips the blacklist step instead of failing the call, so a
     *     client holding an already-dead token can still complete a logout
     * @return always {@code 200}, with a {@code Set-Cookie} that expires the refresh cookie
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null) {
            if (authHeader.startsWith("Bearer ")) {
                String jwt = authHeader.substring(7);
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                User user = (User) auth.getPrincipal();

                authSecurityService.logoutUserSession(user.getId(), jwtService.extractJti(jwt), jwtService.extractExpirationDate(jwt));
            }
        }

        ResponseCookie clearRefresh = ResponseCookie.from("Refresh-Token", "").maxAge(0).path("/").build();
        
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearRefresh.toString())
                .body(Map.of("message", "Logged out successfully"));
    }

    private ResponseCookie createRefreshTokenCookie(Long userId) {
        String rawToken = UUID.randomUUID().toString();
        refreshTokenRepository.save(new RefreshToken(userId, hashString(rawToken)));
        
        return ResponseCookie.from("Refresh-Token", rawToken)
                .httpOnly(true).secure(true).path("/")
                .maxAge(86400)
                .build();
    }

    private String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encodedHash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash", e);
        }
    }
    private User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}