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

// @RestController is @Controller + @ResponseBody combined, means every method here returns
// its value straight as the http response body (usually as json) instead of a view name
// @RequestMapping on the class sets a shared prefix, so every endpoint below builds on /api/v1/auth
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

    // learned spring does not need an @Autowired annotation here since there is only one
    // constructor, it just automatically injects all five dependencies through this one
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

    // ==========================================
    // 0. Registration
    // ==========================================

    private static final int MIN_PASSWORD_LENGTH = 8;

    // Deliberately loose - just enough to catch a typo like a missing @ before it becomes an address
    // nothing can ever be delivered to. Real deliverability is SendGrid's problem, not a regex's.
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

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
        // Required for new registrations even though the column is nullable: the column has to allow
        // nulls for accounts that predate it, but there's no reason to create another one.
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

        // Store the number in E.164 or not at all. 2FA codes go out by email now, but the number
        // still rides along on the 2FA event as the fallback contact, and the SMS provider rejects
        // any other format - so a number accepted here in the wrong shape is undeliverable later.
        String normalizedPhone = null;
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            normalizedPhone = phoneNumberNormalizer.normalize(phoneNumber)
                    .orElse(null);
            if (normalizedPhone == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Please enter a valid phone number, e.g. 571-285-6947 or +15712856947"));
            }
            // Checked on the normalized value, and only once normalization has succeeded - the same
            // phone typed two different ways is still one phone, and comparing the raw input would
            // miss it. Rejected for the same reason a duplicate email is: the number receives that
            // account's 2FA codes, so sharing one hands a second person the keys to the first
            // person's login.
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

    // ==========================================
    // 1. Initial Login Phase
    // ==========================================

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request,
                                   @CookieValue(name = "Device-ID", required = false) String deviceCookie) {
        
        String username = request.get("username");
        String password = request.get("password");

        // 1. Verify password (Throws BadCredentialsException if wrong)
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password)
        );
        User user = (User) authentication.getPrincipal(); // Assuming custom UserDetails implementation

        // 2. Check Device Fingerprint
        boolean isRecognized = authSecurityService.isDeviceRecognized(user.getId(), deviceCookie);

        if (isRecognized) {
            return handleRecognizedDeviceLogin(user);
        } else {
            return handleUnrecognizedDeviceLogin(user);
        }
    }

    private ResponseEntity<?> handleRecognizedDeviceLogin(User user) {
        // Bypass 2FA - Issue Full Access
        String fullJwt = jwtService.generateToken(user, TokenType.FULL_AUTH);
        ResponseCookie refreshCookie = createRefreshTokenCookie(user.getId());

        // learned you can call .header() more than once on a responseentity builder to stack
        // multiple response headers before finally calling .body() to close it out
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(Map.of("status", "SUCCESS", "access_token", fullJwt));
    }

    private ResponseEntity<?> handleUnrecognizedDeviceLogin(User user) {
        // Unrecognized Device - Require 2FA
        return issue2faChallenge(user);
    }

    // Shared by the initial login and by /verify-2fa/resend, so the two can never drift into
    // answering with different shapes for what is, to the client, the same "we sent you a code"
    // state. The code itself is not in here and never comes back from the service - the body
    // carries only the lifetime the UI counts down from.
    private ResponseEntity<?> issue2faChallenge(User user) {
        String preAuthJwt = jwtService.generateToken(user, TokenType.PRE_AUTH);
        // Email is passed straight off the User row - auth-service owns that column, so there's no
        // lookup to make and nothing downstream has to ask another service who this address belongs to.
        int expiresInSeconds = authSecurityService.trigger2fa(user.getId(), user.getPhoneNumber(), user.getEmail());

        // Map.of, not a HashMap: no branch fills this in conditionally any more, and the value has
        // to stay a number so the client can do arithmetic on it without parsing a string.
        return ResponseEntity.accepted().body(Map.of(
                "status", "2FA_REQUIRED",
                "pre_auth_token", preAuthJwt,
                "expires_in_seconds", expiresInSeconds));
    }

    // ==========================================
    // 2. 2FA Verification Phase
    // ==========================================

    private static final String NOT_AUTHENTICATED_MESSAGE = "Not authenticated. Please log in again.";

    @PostMapping("/verify-2fa/sms")
    public ResponseEntity<?> verifySms(@RequestBody Map<String, String> request) {

        // Thanks to our JwtAuthenticationFilter, we ALREADY know who this user is securely!
        // learned securitycontextholder is thread local storage spring security fills in per request,
        // the filter runs earlier in the chain and sets this authentication before this method ever runs
        User user = currentPreAuthUser();
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", NOT_AUTHENTICATED_MESSAGE));
        }
        String code = request.get("code");

        // 1. Verify the code
        TwoFaResult result = authSecurityService.verifySms2fa(user.getId(), code);

        // Each failure gets its own status and a machine-readable reason, because the client has to
        // react differently to each: a wrong digit means try again, an expired or missing code means
        // press resend, and a lockout means start the login over. A flat 401 for all four left the
        // UI guessing, and the two that used to throw came back as 500s.
        return switch (result) {
            // 2. Success! Issue Full Access, generate new Device ID, and new Refresh Token
            case VALID -> buildSuccessfulAuthResponse(user);
            case INVALID -> ResponseEntity.status(401)
                    .body(Map.of("error", "Invalid 2FA code", "reason", "INVALID"));
            case NO_CODE -> ResponseEntity.status(401)
                    .body(Map.of("error", "No active code. Request a new one.", "reason", "NO_CODE"));
            // 410 Gone rather than 401: the credential was real, it just no longer exists, and the
            // frontend keys its "expired, ask for another" state off this status.
            case EXPIRED -> ResponseEntity.status(410)
                    .body(Map.of("error", "Your code has expired. Request a new one.", "reason", "EXPIRED"));
            case LOCKED -> ResponseEntity.status(429)
                    .body(Map.of("error", "Too many failed attempts. Please log in again.", "reason", "LOCKED"));
        };
    }

    // Resending is a separate endpoint rather than a flag on login, because the user has already
    // passed the password check and re-posting credentials to get a second code would mean the
    // frontend holding on to the password for the length of the 2FA screen.
    //
    // The path is load-bearing. JwtAuthenticationFilter only lets a PRE_AUTH token through on URIs
    // containing /api/v1/auth/verify-2fa, and SecurityConfig already permits /verify-2fa/** - so
    // living under that prefix is what makes this reachable at all with the half-authenticated
    // token the caller is holding. Anywhere else would be a 403 from the filter.
    @PostMapping("/verify-2fa/resend")
    public ResponseEntity<?> resend2fa() {
        User user = currentPreAuthUser();
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", NOT_AUTHENTICATED_MESSAGE));
        }

        // Checked before triggering, since trigger2fa's first act is to delete the row this reads.
        int retryAfterSeconds = authSecurityService.secondsUntilResendAllowed(user.getId());
        if (retryAfterSeconds > 0) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "Please wait before requesting another code.",
                    "retry_after_seconds", retryAfterSeconds));
        }

        // A fresh pre-auth token comes back with the new code on purpose. The original one is only
        // good for 5 minutes from login, so a user who resends twice would otherwise end up holding
        // a code that works and a session token that has already died - stranded on the 2FA screen
        // with no way forward except starting over.
        return issue2faChallenge(user);
    }

    // Both 2FA endpoints sit behind permitAll (the caller has no FULL_AUTH token yet, by
    // definition), so a request arriving with no Authorization header at all still reaches the
    // controller - with either a null Authentication or Spring's anonymous one, whose principal is
    // the String "anonymousUser". Casting either straight to User is a 500. Returns null so the
    // caller can answer 401, which is what the client can actually act on.
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

        // httpOnly means javascript in the browser cannot read this cookie at all, blocks a whole
        // class of xss attacks, secure means it only ever gets sent over an actual https connection
        ResponseCookie deviceCookie = ResponseCookie.from("Device-ID", rawDeviceId)
                .httpOnly(true).secure(true).path("/").maxAge(31536000) // 1 Year
                .build();

        ResponseCookie refreshCookie = createRefreshTokenCookie(user.getId());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, deviceCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(Map.of("status", "SUCCESS", "access_token", fullJwt));
    }

    // ==========================================
    // 3. Sliding Session Refresh Phase
    // ==========================================

    // required = false on @CookieValue means this stays null instead of spring throwing a
    // 400 automatically when the cookie is missing, lets me handle the missing case myself below
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshSession(@CookieValue(name = "Refresh-Token", required = false) String refreshToken) {
        if (refreshToken == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Refresh token missing"));
        }

        RefreshToken storedToken = validateAndFetchRefreshToken(refreshToken);
        if (storedToken == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Refresh token expired or revoked"));
        }

        // Token is valid! Issue a fresh 15-minute Access Token
        User user = getUserById(storedToken.getUserId()); // Utility to fetch user
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

    // ==========================================
    // 4. Explicit Logout Phase
    // ==========================================

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null) {
            if (authHeader.startsWith("Bearer ")) {
                String jwt = authHeader.substring(7);
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                User user = (User) auth.getPrincipal();

                // Blacklist the token and revoke session
                authSecurityService.logoutUserSession(user.getId(), jwtService.extractJti(jwt), jwtService.extractExpirationDate(jwt));
            }
        }

        // Destroy the cookies on the frontend
        ResponseCookie clearRefresh = ResponseCookie.from("Refresh-Token", "").maxAge(0).path("/").build();
        
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearRefresh.toString())
                .body(Map.of("message", "Logged out successfully"));
    }

    // ==========================================
    // Internal Utilities
    // ==========================================

    private ResponseCookie createRefreshTokenCookie(Long userId) {
        String rawToken = UUID.randomUUID().toString();
        refreshTokenRepository.save(new RefreshToken(userId, hashString(rawToken)));
        
        return ResponseCookie.from("Refresh-Token", rawToken)
                .httpOnly(true).secure(true).path("/") // Path "/" means it's sent on all endpoints
                .maxAge(86400) // 24 Hours
                .build();
    }

    // messagedigest.getinstance can throw a checked exception if the algorithm name is unknown
    // to the jvm, sha-256 always exists so wrapping it in a runtimeexception here just avoids
    // forcing every caller to declare a throws clause for something that in practice never happens
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