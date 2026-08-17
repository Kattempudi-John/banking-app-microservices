package com.example.authservice;

import com.example.authservice.model.BlacklistedToken;
import com.example.authservice.model.RecognizedDevice;
import com.example.authservice.model.RefreshToken;
import com.example.authservice.model.TwoFactorCode;
import com.example.authservice.model.User;
import com.example.authservice.repository.BlacklistedTokenRepository;
import com.example.authservice.repository.RecognizedDeviceRepository;
import com.example.authservice.repository.RefreshTokenRepository;
import com.example.authservice.repository.TwoFactorCodeRepository;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.security.TokenType;
import com.example.authservice.service.AuthSecurityService;
import com.example.authservice.service.JwtService;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Covers the whole credential lifecycle of auth-service: device recognition, the two-phase
 * login, 2FA issue / verify / resend, refresh, logout and blacklisting, registration, and the
 * internal phone-number API that profile-service calls.
 *
 * <h2>Slice: why {@code @SpringBootTest} rather than {@code @WebMvcTest}</h2>
 * Most of what is under test here <em>is</em> the filter chain. A PRE_AUTH token being refused
 * everywhere except {@code /verify-2fa}, a blacklisted {@code jti} being turned away, an
 * internal endpoint demanding a shared secret — none of that lives in a controller, and
 * {@code @WebMvcTest} would hand back a mocked {@code JwtService} and a stubbed security setup
 * that made every one of those assertions vacuous. A full context also lets several tests call
 * {@link AuthSecurityService} directly, which is not a web slice at all.
 * {@code @AutoConfigureMockMvc} then drives the real chain in-process, with no server socket.
 *
 * <h2>What is real and what is mocked</h2>
 * This is the part that catches people out. The <strong>real</strong> beans are
 * {@link JwtService} (tokens are genuinely signed and parsed, so a token minted in a test is
 * one the filter will accept), {@link AuthSecurityService}, {@link PasswordEncoder} (so
 * {@code passwordEncoder.matches} in the registration test is a true BCrypt check), plus the
 * controllers, the security filter chain, and the phone-number normalizer.
 *
 * <p>Nine collaborators are {@code @MockBean}s:
 * <ul>
 *   <li>{@code AuthenticationManager} / {@code AuthenticationProvider} / {@code UserDetailsService}
 *       — stand in for real credential checking, so the login tests can decide the outcome of
 *       the password step without a password ever being stored;</li>
 *   <li>{@code RecognizedDeviceRepository}, {@code TwoFactorCodeRepository},
 *       {@code RefreshTokenRepository}, {@code UserRepository},
 *       {@code BlacklistedTokenRepository} — every persistence collaborator, so each test
 *       states the exact row the service will read;</li>
 *   <li>{@code KafkaTemplate} — stands in for the broker, and doubles as the assertion surface:
 *       captured payloads are how the outbound {@code notification-events} and
 *       {@code user-events} contracts get pinned.</li>
 * </ul>
 *
 * <p>Because every repository is mocked, <strong>nothing is ever written to a database</strong>.
 * The H2 URL in {@code @TestPropertySource} exists only so JPA and Hibernate can bootstrap and
 * the context can start; {@code ddl-auto=create-drop} builds a schema no test ever reads.
 * Kafka autoconfiguration is excluded outright — there is no broker, embedded or otherwise, and
 * no Testcontainers here.
 *
 * <h2>Transactional behaviour</h2>
 * The class is not {@code @Transactional} and nothing rolls back between tests, because there
 * is no state to roll back. Where a service method is {@code @Transactional}, this suite sees
 * only the calls it made on the mocks. That matters for the EXPIRED and LOCKED verify cases:
 * what those two guard is that the method <em>returns</em> a result instead of throwing (the
 * 410 / 429 rather than a 500) <em>and</em> that it called {@code delete} on the way. Together
 * those are exactly the two halves that were broken when it threw and the delete rolled back.
 *
 * <h2>{@code @TestPropertySource}: the internal token</h2>
 * {@code application.security.internal-token} is pinned to {@code test-internal-token}, which
 * is deliberately <em>not</em> the value {@code InternalTokenFilter} falls back to in dev. If
 * the filter ever compared against a hardcoded constant instead of the configured property,
 * these tests would fail rather than quietly keep passing.
 *
 * <h2>Shared fixture</h2>
 * {@link #setUp()} runs {@code @BeforeEach} (there is no {@code @BeforeAll} state) and rebuilds
 * one mocked {@link User} — {@code johndoe}, id 1, {@code +15551234567},
 * {@code johndoe@example.com} — and registers it with the mocked {@code UserDetailsService}.
 * Spring resets every {@code @MockBean} between tests, so {@code verify} counts start at zero
 * and no test in this class depends on the order it runs in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
    // pinned here rather than relying on the filter's built-in dev default, so these tests keep
    // asserting the same thing if that default is ever changed or overridden in a real environment
    "application.security.internal-token=test-internal-token"
})
class AuthManagementTestSuite {

    // the shared secret internal service-to-service callers present, see InternalTokenFilter
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String INTERNAL_TOKEN = "test-internal-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AuthSecurityService authSecurityService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private AuthenticationManager authenticationManager;

    @MockBean
    private AuthenticationProvider authenticationProvider;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private RecognizedDeviceRepository deviceRepository;

    @MockBean
    private TwoFactorCodeRepository twoFactorCodeRepository;

    @MockBean
    private RefreshTokenRepository refreshTokenRepository;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private BlacklistedTokenRepository blacklistedTokenRepository;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    private User mockUser;

    @BeforeEach
    void setUp() {
        mockUser = mock(User.class);
        given(mockUser.getUsername()).willReturn("johndoe");
        given(mockUser.getId()).willReturn(1L);
        given(mockUser.getPhoneNumber()).willReturn("+15551234567");
        // the login path reads the address off the user to put on the 2FA event; an unstubbed
        // mock would hand back null and the payload would quietly lose it
        given(mockUser.getEmail()).willReturn("johndoe@example.com");

        given(userDetailsService.loadUserByUsername("johndoe")).willReturn(mockUser);
    }

    // ==========================================
    // Device Recognition
    // ==========================================

    @Test
    @DisplayName("Device Recognition: No Stored Hash For This Cookie Reports Unrecognized - [MEANT TO FAIL]")
    void isDeviceRecognized_noStoredHashForCookie_returnsFalse() {
        given(deviceRepository.findByUserIdAndDeviceHash(eq(1L), any(String.class)))
                .willReturn(Optional.empty());

        boolean recognized = authSecurityService.isDeviceRecognized(1L, "invalid-device-cookie-123");

        assertThat(recognized).isFalse();
        verify(deviceRepository).findByUserIdAndDeviceHash(eq(1L), any(String.class));
    }

    @Test
    @DisplayName("Device Recognition: Stored Hash Found For This Cookie Reports Recognized - [MEANT TO PASS]")
    void isDeviceRecognized_storedHashFoundForCookie_returnsTrue() {
        given(deviceRepository.findByUserIdAndDeviceHash(eq(1L), any(String.class)))
                .willReturn(Optional.of(new RecognizedDevice(1L, "hashed-cookie")));

        boolean recognized = authSecurityService.isDeviceRecognized(1L, "valid-device-cookie-123");

        assertThat(recognized).isTrue();
        verify(deviceRepository).findByUserIdAndDeviceHash(eq(1L), any(String.class));
    }

    // ==========================================
    // Login and 2FA Issue
    // ==========================================

    /**
     * The {@code demoCode} assertion is a regression guard rather than a shape check: the login
     * response used to carry the 2FA code itself whenever {@code app.demo.enabled} was on, which
     * handed the second factor straight back down the same response as the first one. It must
     * never reappear under any flag.
     */
    @Test
    @DisplayName("Login: Unrecognized Device Returns 202 With A Pre-Auth Token And No Code - [MEANT TO PASS]")
    void login_unrecognizedDevice_returns202WithPreAuthTokenAndNoCode() throws Exception {
        Authentication authentication = mock(Authentication.class);
        given(authentication.getPrincipal()).willReturn(mockUser);
        given(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .willReturn(authentication);
        given(deviceRepository.findByUserIdAndDeviceHash(eq(1L), any())).willReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"johndoe\",\"password\":\"SecurePass123!\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("2FA_REQUIRED"))
                .andExpect(jsonPath("$.pre_auth_token").exists())
                // the screen counts down from this, so the client is not left hardcoding a
                // duration that this service owns
                .andExpect(jsonPath("$.expires_in_seconds").value(180))
                .andExpect(jsonPath("$.demoCode").doesNotExist());
    }

    @Test
    @DisplayName("Trigger 2FA: Clears The Old Code, Saves A New One And Publishes To Kafka - [MEANT TO PASS]")
    void trigger2fa_anyUser_deletesOldCodeSavesNewOneAndPublishesEvent() {
        authSecurityService.trigger2fa(1L, "+15551234567", "johndoe@example.com");

        verify(twoFactorCodeRepository).deleteByUserId(1L);
        verify(twoFactorCodeRepository).save(any(TwoFactorCode.class));
        verify(kafkaTemplate).send(eq("notification-events"), any(String.class));
    }

    /**
     * The payload is the contract notification-service consumes, so this pins the field names
     * rather than trusting that a message merely got sent. A silent rename or a dropped key here
     * breaks the other side with no failure showing up anywhere in this service.
     */
    @Test
    @DisplayName("Trigger 2FA: Publishes A TWO_FA_REQUESTED Payload Carrying Email And TTL - [MEANT TO PASS]")
    void trigger2fa_anyUser_publishesTwoFaRequestedPayloadWithEmailAndTtl() {
        authSecurityService.trigger2fa(1L, "+15551234567", "johndoe@example.com");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("notification-events"), payloadCaptor.capture());

        String payload = payloadCaptor.getValue();
        assertThat(payload).contains("\"action\":\"TWO_FA_REQUESTED\"");
        assertThat(payload).contains("\"userId\":\"1\"");
        assertThat(payload).contains("\"email\":\"johndoe@example.com\"");
        assertThat(payload).contains("\"phoneNumber\":\"+15551234567\"");
        assertThat(payload).contains("\"code\":");
        // the email tells the user how long the code lasts, so the lifetime has to travel with it -
        // otherwise notification-service has to hardcode its own guess, and the message and the
        // on-screen countdown drift apart the moment this service's TTL is retuned
        assertThat(payload).contains("\"expiresInSeconds\":\"180\"");
    }

    /**
     * The TTL is what the whole countdown hangs off, so it comes back from the service rather
     * than being reconstructed by the caller — and the code itself deliberately does not, which
     * is the point: the raw secret has no business reaching the controller layer.
     */
    @Test
    @DisplayName("Trigger 2FA: Returns The Code Lifetime, Not The Code - [MEANT TO PASS]")
    void trigger2fa_anyUser_returnsTtlSecondsRatherThanTheCode() {
        int expiresInSeconds = authSecurityService.trigger2fa(1L, "+15551234567", "johndoe@example.com");

        assertThat(expiresInSeconds).isEqualTo(180);
    }

    // ==========================================
    // 2FA Verification
    // ==========================================

    @Test
    @DisplayName("Verify 2FA: Correct Code Returns 200 With Session JWT, Device Cookie And Burns The Code - [MEANT TO PASS]")
    void verify2faSms_correctCode_returns200WithAccessTokenDeviceCookieAndDeletesCode() throws Exception {
        String preAuthToken = jwtService.generateToken(mockUser, TokenType.PRE_AUTH);
        TwoFactorCode validCode = new TwoFactorCode(1L, hashString("123456"));
        given(twoFactorCodeRepository.findByUserId(1L)).willReturn(Optional.of(validCode));

        mockMvc.perform(post("/api/v1/auth/verify-2fa/sms")
                .header("Authorization", "Bearer " + preAuthToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(header().exists("Set-Cookie"));

        // a code is single-use: deleting it is what stops the same six digits being replayed
        verify(twoFactorCodeRepository).delete(validCode);
    }

    // These four outcomes used to be indistinguishable to the client. A wrong digit and a missing
    // code both came back as a bare 401 "Invalid 2FA code", while expired and locked-out threw out
    // of a @Transactional method and surfaced as 500s. Each now gets its own status and a
    // machine-readable reason, and each one that clears a row actually clears it.

    @Test
    @DisplayName("Verify 2FA: Wrong Code Returns 401 With INVALID Reason And Burns An Attempt - [MEANT TO FAIL]")
    void verify2faSms_wrongCode_returns401InvalidAndIncrementsAttempts() throws Exception {
        String preAuthToken = jwtService.generateToken(mockUser, TokenType.PRE_AUTH);
        TwoFactorCode storedCode = new TwoFactorCode(1L, hashString("123456"));
        given(twoFactorCodeRepository.findByUserId(1L)).willReturn(Optional.of(storedCode));

        mockMvc.perform(post("/api/v1/auth/verify-2fa/sms")
                .header("Authorization", "Bearer " + preAuthToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid 2FA code"))
                .andExpect(jsonPath("$.reason").value("INVALID"));

        // the attempt counter is the only thing standing between a 6-digit code and brute force,
        // so a wrong guess has to be written back, not just rejected
        assertThat(storedCode.getAttempts()).isEqualTo(1);
        verify(twoFactorCodeRepository).save(storedCode);
        verify(twoFactorCodeRepository, never()).delete(storedCode);
    }

    /**
     * An expired code is gone rather than wrong, so 410 — the frontend keys its "expired, press
     * resend" state off this status.
     *
     * <p>Regression guard: this branch used to throw, which meant a 500 <em>and</em>, because the
     * throw rolled back the enclosing {@code @Transactional} method, the expired row survived the
     * request that had just deleted it. The status and the {@code delete} verify below are the
     * two halves of that bug, which is why both are asserted together.
     */
    @Test
    @DisplayName("Verify 2FA: Expired Code Returns 410 And The Dead Row Is Deleted - [MEANT TO FAIL]")
    void verify2faSms_expiredCode_returns410ExpiredAndDeletesRow() throws Exception {
        String preAuthToken = jwtService.generateToken(mockUser, TokenType.PRE_AUTH);
        // negative TTL puts expires_at a second in the past, which is what the entity's own
        // isExpired() reads - no clock mocking needed
        TwoFactorCode expiredCode = new TwoFactorCode(1L, hashString("123456"), -1);
        given(twoFactorCodeRepository.findByUserId(1L)).willReturn(Optional.of(expiredCode));

        mockMvc.perform(post("/api/v1/auth/verify-2fa/sms")
                .header("Authorization", "Bearer " + preAuthToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("Your code has expired. Request a new one."))
                .andExpect(jsonPath("$.reason").value("EXPIRED"));

        verify(twoFactorCodeRepository).delete(expiredCode);
    }

    /**
     * Three wrong guesses ends the attempt entirely, so 429 rather than another 401 — there is no
     * "try again" left to offer.
     *
     * <p>Regression guard, same shape as the expired branch: it threw, so the row that was
     * supposed to be cleared stayed put and the next request read the same locked code back.
     */
    @Test
    @DisplayName("Verify 2FA: Attempt Limit Reached Returns 429 And Clears The Code - [MEANT TO FAIL]")
    void verify2faSms_attemptLimitReached_returns429LockedAndDeletesRow() throws Exception {
        String preAuthToken = jwtService.generateToken(mockUser, TokenType.PRE_AUTH);
        TwoFactorCode lockedCode = new TwoFactorCode(1L, hashString("123456"));
        lockedCode.setAttempts(3);
        given(twoFactorCodeRepository.findByUserId(1L)).willReturn(Optional.of(lockedCode));

        // the submitted digits are the *correct* ones on purpose: the lockout is checked before
        // the comparison, so even a right answer is refused once the limit is hit
        mockMvc.perform(post("/api/v1/auth/verify-2fa/sms")
                .header("Authorization", "Bearer " + preAuthToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Too many failed attempts. Please log in again."))
                .andExpect(jsonPath("$.reason").value("LOCKED"));

        verify(twoFactorCodeRepository).delete(lockedCode);
    }

    /**
     * Submitting against no code at all is its own case: the user has nothing to retype, so the
     * message points at resend instead of telling them they got the digits wrong.
     */
    @Test
    @DisplayName("Verify 2FA: No Active Code Returns 401 With NO_CODE Reason - [MEANT TO FAIL]")
    void verify2faSms_noCodeOnFile_returns401NoCode() throws Exception {
        String preAuthToken = jwtService.generateToken(mockUser, TokenType.PRE_AUTH);
        given(twoFactorCodeRepository.findByUserId(1L)).willReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/auth/verify-2fa/sms")
                .header("Authorization", "Bearer " + preAuthToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No active code. Request a new one."))
                .andExpect(jsonPath("$.reason").value("NO_CODE"));
    }

    /**
     * {@code /verify-2fa/**} is {@code permitAll}, so a request with no {@code Authorization}
     * header is not stopped by the filter chain — it lands in the controller with Spring's
     * anonymous principal. Casting that to {@code User} is a {@code ClassCastException} and a
     * 500; the caller has to get a 401 it can actually act on. The {@code never()} below is what
     * proves the controller bailed out before touching the code at all.
     */
    @Test
    @DisplayName("Verify 2FA: No Authorization Header Returns 401 Rather Than 500 - [MEANT TO FAIL]")
    void verify2faSms_noAuthorizationHeader_returns401WithoutQueryingCode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-2fa/sms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());

        verify(twoFactorCodeRepository, never()).findByUserId(any());
    }

    // ==========================================
    // 2FA Resend
    // ==========================================

    /**
     * The path has to sit under {@code /verify-2fa}, because that is the only prefix
     * {@code JwtAuthenticationFilter} lets a PRE_AUTH token through on. A resend endpoint
     * anywhere else would be answered with the filter's 403 "Partial authentication" before the
     * controller ever ran — and the user holding a half-authenticated token is precisely the only
     * user who can ever need this endpoint.
     */
    @Test
    @DisplayName("Resend 2FA: PRE_AUTH Token Accepted And A New Code Is Issued - [MEANT TO PASS]")
    void resend2fa_preAuthTokenAndNoCodeOnFile_returns202AndIssuesNewCode() throws Exception {
        String preAuthToken = jwtService.generateToken(mockUser, TokenType.PRE_AUTH);
        // no code on file, so there is no cooldown window to wait out
        given(twoFactorCodeRepository.findByUserId(1L)).willReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/auth/verify-2fa/resend")
                .header("Authorization", "Bearer " + preAuthToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("2FA_REQUIRED"))
                .andExpect(jsonPath("$.pre_auth_token").exists())
                .andExpect(jsonPath("$.expires_in_seconds").value(180))
                // the resend response is the same body as login, and it must stay just as free of
                // the code itself
                .andExpect(jsonPath("$.demoCode").doesNotExist());

        // a resend is a whole new code, not a re-send of the old one - the old row goes and a
        // fresh one is saved, otherwise the code in the second email would not match the database
        verify(twoFactorCodeRepository).deleteByUserId(1L);
        verify(twoFactorCodeRepository).save(any(TwoFactorCode.class));
        verify(kafkaTemplate).send(eq("notification-events"), any(String.class));
    }

    /**
     * The pre-auth token is only good for 5 minutes from login while each code lasts 3, so a user
     * who resends twice would be holding a live code and a dead session. The response has to hand
     * back a fresh token or the second resend strands them on a screen that can no longer submit.
     */
    @Test
    @DisplayName("Resend 2FA: Response Carries A Freshly Minted Pre-Auth Token - [MEANT TO PASS]")
    void resend2fa_preAuthToken_returnsFreshPreAuthTokenWithDistinctJti() throws Exception {
        String originalToken = jwtService.generateToken(mockUser, TokenType.PRE_AUTH);
        given(twoFactorCodeRepository.findByUserId(1L)).willReturn(Optional.empty());

        String body = mockMvc.perform(post("/api/v1/auth/verify-2fa/resend")
                .header("Authorization", "Bearer " + originalToken))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String reissued = JsonPath.read(body, "$.pre_auth_token");
        // still a PRE_AUTH token addressed to the same user - resending must not quietly promote
        // anyone past the second factor they have not completed yet
        assertThat(jwtService.extractTokenType(reissued)).isEqualTo(TokenType.PRE_AUTH);
        assertThat(jwtService.extractUsername(reissued)).isEqualTo("johndoe");
        // and it is genuinely a new token, not the one that came in - a distinct jti proves the
        // expiry clock restarted rather than the same 5 minutes continuing to run down
        assertThat(jwtService.extractJti(reissued)).isNotEqualTo(jwtService.extractJti(originalToken));
    }

    /**
     * Without a cooldown a held-down resend button fans out a mailbox full of codes and burns
     * through the email provider's quota. The window is measured off the existing row's
     * {@code created_at}, which is already recorded — no extra table, no extra state to keep in
     * sync.
     */
    @Test
    @DisplayName("Resend 2FA: Second Request Inside The 30-Second Cooldown Rejected With 429 - [MEANT TO FAIL]")
    void resend2fa_withinThirtySecondCooldown_returns429AndMintsNothing() throws Exception {
        String preAuthToken = jwtService.generateToken(mockUser, TokenType.PRE_AUTH);
        // constructed now, so created_at is now and the full 30 seconds are still outstanding
        TwoFactorCode justIssued = new TwoFactorCode(1L, hashString("123456"));
        given(twoFactorCodeRepository.findByUserId(1L)).willReturn(Optional.of(justIssued));

        mockMvc.perform(post("/api/v1/auth/verify-2fa/resend")
                .header("Authorization", "Bearer " + preAuthToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Please wait before requesting another code."))
                // the client shows a disabled button counting down from this, so it has to be a
                // number and it has to be inside the window rather than some larger leftover
                .andExpect(jsonPath("$.retry_after_seconds").isNumber())
                .andExpect(jsonPath("$.retry_after_seconds").value(Matchers.lessThanOrEqualTo(30)))
                .andExpect(jsonPath("$.retry_after_seconds").value(Matchers.greaterThan(0)));

        // the refusal has to happen before anything is minted, otherwise the rejected request would
        // still have invalidated the code the user is currently looking at
        verify(twoFactorCodeRepository, never()).deleteByUserId(any());
        verify(twoFactorCodeRepository, never()).save(any(TwoFactorCode.class));
        verify(kafkaTemplate, never()).send(eq("notification-events"), any(String.class));
    }

    /**
     * The cooldown is a delay, not a one-shot lock: a user whose first email never arrived must
     * not be left with a permanently dead form.
     */
    @Test
    @DisplayName("Resend 2FA: Request After The Cooldown Elapses Is Allowed - [MEANT TO PASS]")
    void resend2fa_afterCooldownElapsed_returns202AndPublishesEvent() throws Exception {
        String preAuthToken = jwtService.generateToken(mockUser, TokenType.PRE_AUTH);
        // 45s puts created_at well clear of the 30s window; created_at has no setter (the column
        // is not meant to be rewritten), so the row's age is stubbed rather than assigned
        TwoFactorCode staleCode = mock(TwoFactorCode.class);
        given(staleCode.getCreatedAt()).willReturn(LocalDateTime.now().minusSeconds(45));
        given(twoFactorCodeRepository.findByUserId(1L)).willReturn(Optional.of(staleCode));

        mockMvc.perform(post("/api/v1/auth/verify-2fa/resend")
                .header("Authorization", "Bearer " + preAuthToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.expires_in_seconds").value(180));

        verify(kafkaTemplate).send(eq("notification-events"), any(String.class));
    }

    /**
     * Same {@code permitAll} hole as the verify endpoint: nothing in the filter chain stops a
     * headerless request, so the controller itself has to refuse it rather than casting the
     * anonymous principal to {@code User} and turning a missing header into a 500.
     */
    @Test
    @DisplayName("Resend 2FA: No Authorization Header Returns 401 Rather Than 500 - [MEANT TO FAIL]")
    void resend2fa_noAuthorizationHeader_returns401AndMintsNothing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-2fa/resend"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());

        // nothing was minted or announced on the back of an unauthenticated request
        verify(twoFactorCodeRepository, never()).save(any(TwoFactorCode.class));
        verify(kafkaTemplate, never()).send(eq("notification-events"), any(String.class));
    }

    // ==========================================
    // Token Boundaries: PRE_AUTH vs FULL_AUTH
    // ==========================================

    @Test
    @DisplayName("Logout: PRE_AUTH Token Refused At A Protected Endpoint With 403 - [MEANT TO FAIL]")
    void logout_preAuthToken_returns403PartialAuthentication() throws Exception {
        String preAuthToken = jwtService.generateToken(mockUser, TokenType.PRE_AUTH);

        // GET against a POST-only mapping on purpose: JwtAuthenticationFilter turns the PRE_AUTH
        // token away before Spring MVC ever matches a handler, so the method is irrelevant here -
        // a 405 instead of this 403 would mean the boundary had moved into the controller layer
        mockMvc.perform(get("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + preAuthToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Partial authentication. 2FA verification required."));
    }

    @Test
    @DisplayName("Logout: FULL_AUTH Token Carries Its Claims And Is Accepted With 200 - [MEANT TO PASS]")
    void logout_fullAuthToken_returns200AndTokenCarriesFullAuthClaims() throws Exception {
        String fullAuthToken = jwtService.generateToken(mockUser, TokenType.FULL_AUTH);

        assertThat(jwtService.extractTokenType(fullAuthToken)).isEqualTo(TokenType.FULL_AUTH);
        assertThat(jwtService.extractUsername(fullAuthToken)).isEqualTo("johndoe");

        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + fullAuthToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"));
    }

    // ==========================================
    // Session Refresh
    // ==========================================

    @Test
    @DisplayName("Refresh: No Refresh-Token Cookie Returns 401 - [MEANT TO FAIL]")
    void refresh_noRefreshTokenCookie_returns401TokenMissing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Refresh token missing"));
    }

    @Test
    @DisplayName("Refresh: Revoked Token In The Database Returns 401 - [MEANT TO FAIL]")
    void refresh_revokedToken_returns401ExpiredOrRevoked() throws Exception {
        String rawRefreshToken = "raw-refresh-token-uuid-123";
        // the cookie carries the raw value while the table stores only its SHA-256, so the fixture
        // has to hash it the same way the service will before the stub can ever match
        String hashedToken = hashString(rawRefreshToken);
        RefreshToken revokedToken = new RefreshToken(1L, hashedToken);
        revokedToken.revoke();
        given(refreshTokenRepository.findByTokenHash(hashedToken)).willReturn(Optional.of(revokedToken));

        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(new Cookie("Refresh-Token", rawRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Refresh token expired or revoked"));
    }

    @Test
    @DisplayName("Refresh: Active Token Issues A Brand New FULL_AUTH Access Token - [MEANT TO PASS]")
    void refresh_activeToken_returns200WithNewAccessToken() throws Exception {
        String rawRefreshToken = "raw-refresh-token-uuid-999";
        String hashedToken = hashString(rawRefreshToken);
        RefreshToken activeToken = new RefreshToken(1L, hashedToken);
        given(refreshTokenRepository.findByTokenHash(hashedToken)).willReturn(Optional.of(activeToken));
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));

        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(new Cookie("Refresh-Token", rawRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists());
    }

    // ==========================================
    // Logout and JWT Blacklist
    // ==========================================

    @Test
    @DisplayName("Logout Session: Revokes Every Refresh Token And Blacklists The JTI - [MEANT TO PASS]")
    void logoutUserSession_activeSession_revokesRefreshTokensAndBlacklistsJti() {
        String jti = "test-jwt-uuid-jti-1001";
        // 15 minutes out, i.e. a token still inside its access-token lifetime - the blacklist row
        // only has to outlive the JWT it shadows
        Date expiresAt = new Date(System.currentTimeMillis() + 900000);

        authSecurityService.logoutUserSession(1L, jti, expiresAt);

        verify(refreshTokenRepository).revokeAllUserTokens(1L);
        verify(blacklistedTokenRepository).save(any(BlacklistedToken.class));
    }

    @Test
    @DisplayName("Purge Blacklist: Scheduled Run Executes The Expired-Token Delete Query - [MEANT TO PASS]")
    void purgeExpiredBlacklistTokens_scheduledRun_executesExpiredTokenDelete() {
        authSecurityService.purgeExpiredBlacklistTokens();

        // the cutoff is "now", computed inside the method - the exact instant is not the point,
        // that the scheduled entry point reaches the delete query at all is
        verify(blacklistedTokenRepository).deleteAllExpiredTokensSince(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("Logout: Blacklisted JTI Denied By JwtAuthenticationFilter With 401 - [MEANT TO FAIL]")
    void logout_blacklistedJti_returns401RevokedFromFilter() throws Exception {
        String fullAuthToken = jwtService.generateToken(mockUser, TokenType.FULL_AUTH);
        String jti = jwtService.extractJti(fullAuthToken);
        given(blacklistedTokenRepository.existsById(jti)).willReturn(true);

        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + fullAuthToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("{\"error\": \"Token has been revoked. Please log in again.\"}"));

        // the token is otherwise perfectly valid, so this verify is what proves the rejection came
        // from the blacklist lookup rather than from signature or expiry checking
        verify(blacklistedTokenRepository).existsById(jti);
    }

    // ==========================================
    // Registration
    // ==========================================

    @Test
    @DisplayName("Register: New Username Creates Account With Hashed Password - [MEANT TO PASS]")
    void register_newUsername_returns201AndSavesUserWithHashedPasswordAndEmail() throws Exception {
        given(userRepository.existsByUsername("newuser")).willReturn(false);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"SecurePass123!\",\"phoneNumber\":\"+15551234567\","
                        + "\"email\":\"newuser@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getUsername()).isEqualTo("newuser");
        assertThat(savedUser.getValue().getPassword()).isNotEqualTo("SecurePass123!");
        // the encoder is a real bean, so this is an actual BCrypt verification rather than a
        // "looks different from the plaintext" check
        assertThat(passwordEncoder.matches("SecurePass123!", savedUser.getValue().getPassword())).isTrue();
        // notification-service delivers balance summaries and alerts here - a user saved without one
        // can't be emailed at all
        assertThat(savedUser.getValue().getEmail()).isEqualTo("newuser@example.com");
    }

    /**
     * profile-service and account-service provision their own rows off this event, so it is the
     * only way they learn the user exists.
     */
    @Test
    @DisplayName("Register: New Username Publishes UserRegistered Event To Kafka - [MEANT TO PASS]")
    void register_newUsername_publishesUserRegisteredEventWithGeneratedId() throws Exception {
        given(userRepository.existsByUsername("newuser")).willReturn(false);
        // mimics Hibernate populating the generated id on the passed-in entity; a plain mock would
        // return null and the event would go out with no userId to key on
        given(userRepository.save(any(User.class))).willAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"SecurePass123!\",\"phoneNumber\":\"+15551234567\","
                        + "\"email\":\"newuser@example.com\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("user-events"), payload.capture());
        assertThat(payload.getValue()).contains("\"userId\":\"99\"");
        assertThat(payload.getValue()).contains("\"username\":\"newuser\"");
        assertThat(payload.getValue()).contains("\"phoneNumber\":\"+15551234567\"");
        // the address has to travel on the event itself - profile-service stores it from here, and
        // notification-service reads it back from profile-service when it needs to send anything
        assertThat(payload.getValue()).contains("\"email\":\"newuser@example.com\"");
    }

    @Test
    @DisplayName("Register: Duplicate Username Rejected With Conflict - [MEANT TO FAIL]")
    void register_usernameAlreadyTaken_returns409AndPublishesNothing() throws Exception {
        given(userRepository.existsByUsername("johndoe")).willReturn(true);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"johndoe\",\"password\":\"SecurePass123!\",\"email\":\"johndoe@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Username is already taken"));

        verify(kafkaTemplate, never()).send(eq("user-events"), any(String.class));
    }

    @Test
    @DisplayName("Register: Missing Password Rejected - [MEANT TO FAIL]")
    void register_passwordMissing_returns400AndPublishesNothing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(kafkaTemplate, never()).send(eq("user-events"), any(String.class));
    }

    @Test
    @DisplayName("Register: Password Under The Minimum Length Rejected - [MEANT TO FAIL]")
    void register_passwordUnderMinimumLength_returns400() throws Exception {
        given(userRepository.existsByUsername("newuser")).willReturn(false);

        // "short" is 5 characters against an 8-character minimum, and the length check runs ahead
        // of the email check, so the absent email in this body is never reached
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /**
     * An account with no address on file can never receive a balance summary or a transaction
     * alert, so registration insists on one even though the column itself is nullable for rows
     * that predate it.
     */
    @Test
    @DisplayName("Register: Missing Email Rejected - [MEANT TO FAIL]")
    void register_emailMissing_returns400AndPublishesNothing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"SecurePass123!\",\"phoneNumber\":\"+15551234567\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Email is required"));

        verify(kafkaTemplate, never()).send(eq("user-events"), any(String.class));
    }

    @Test
    @DisplayName("Register: Malformed Email Rejected - [MEANT TO FAIL]")
    void register_emailWithoutAtSign_returns400AndPublishesNothing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"SecurePass123!\",\"email\":\"not-an-address\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Please enter a valid email address"));

        verify(kafkaTemplate, never()).send(eq("user-events"), any(String.class));
    }

    /**
     * A number stored the way a person types it is silently undeliverable — the SMS provider only
     * accepts E.164 — so registration converts it rather than storing what was typed.
     */
    @Test
    @DisplayName("Register: Phone Number Stored In E.164 Regardless Of Typed Format - [MEANT TO PASS]")
    void register_phoneTypedInLocalFormat_savesE164Number() throws Exception {
        given(userRepository.existsByUsername("newuser")).willReturn(false);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"SecurePass123!\",\"phoneNumber\":\"(571) 285-6947\","
                        + "\"email\":\"newuser@example.com\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getPhoneNumber()).isEqualTo("+15712856947");
    }

    /**
     * Refused rather than stored as-is: a number that cannot be resolved would produce an account
     * that can never complete a 2FA login, with nothing to indicate why.
     */
    @Test
    @DisplayName("Register: Unresolvable Phone Number Rejected - [MEANT TO FAIL]")
    void register_phoneThatCannotBeNormalized_returns400AndPublishesNothing() throws Exception {
        given(userRepository.existsByUsername("newuser")).willReturn(false);

        // no area code, so there is nothing to expand to E.164
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"SecurePass123!\",\"phoneNumber\":\"285-6947\","
                        + "\"email\":\"newuser@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(kafkaTemplate, never()).send(eq("user-events"), any(String.class));
    }

    /**
     * The email column is unique, so this has to be caught up front rather than surfacing as a
     * constraint violation from the insert.
     */
    @Test
    @DisplayName("Register: Duplicate Email Rejected With Conflict - [MEANT TO FAIL]")
    void register_emailAlreadyRegistered_returns409AndPublishesNothing() throws Exception {
        given(userRepository.existsByUsername("newuser")).willReturn(false);
        given(userRepository.existsByEmail("taken@example.com")).willReturn(true);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"SecurePass123!\",\"email\":\"taken@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("That email is already registered"));

        verify(kafkaTemplate, never()).send(eq("user-events"), any(String.class));
    }

    /**
     * The number is where that account's 2FA codes get sent, so a second account on the same phone
     * would let whoever holds it complete either login.
     */
    @Test
    @DisplayName("Register: Duplicate Phone Number Rejected With Conflict - [MEANT TO FAIL]")
    void register_phoneAlreadyRegistered_returns409AndSavesNothing() throws Exception {
        given(userRepository.existsByUsername("newuser")).willReturn(false);
        given(userRepository.existsByEmail("newuser@example.com")).willReturn(false);
        given(userRepository.existsByPhoneNumber("+15712856947")).willReturn(true);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"SecurePass123!\",\"phoneNumber\":\"+15712856947\","
                        + "\"email\":\"newuser@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("That phone number is already registered"));

        verify(userRepository, never()).save(any(User.class));
        verify(kafkaTemplate, never()).send(eq("user-events"), any(String.class));
    }

    /**
     * The stub is keyed on the E.164 form while the request body carries the same number typed
     * locally, so this only passes if the duplicate check runs <em>after</em> normalisation —
     * checking the raw string would miss the collision entirely.
     */
    @Test
    @DisplayName("Register: Duplicate Phone In A Different Format Still Rejected - [MEANT TO FAIL]")
    void register_duplicatePhoneTypedInLocalFormat_returns409AndSavesNothing() throws Exception {
        given(userRepository.existsByUsername("newuser")).willReturn(false);
        given(userRepository.existsByEmail("newuser@example.com")).willReturn(false);
        given(userRepository.existsByPhoneNumber("+15712856947")).willReturn(true);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"SecurePass123!\",\"phoneNumber\":\"(571) 285-6947\","
                        + "\"email\":\"newuser@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("That phone number is already registered"));

        verify(userRepository, never()).save(any(User.class));
        verify(kafkaTemplate, never()).send(eq("user-events"), any(String.class));
    }

    // ==========================================
    // Internal Phone Number API (profile-service)
    // ==========================================

    // profile-service used to own its own copy of the number, which meant the KYC identity form
    // could change it while 2FA codes kept going to the value stored here. These tests pin the
    // contract it now calls instead.

    /**
     * The number is the destination for 2FA codes, so letting a second account claim one already
     * in use would hand whoever holds the phone a way into the first account's login — exactly
     * what the register endpoint already refuses, now refused on the update path too.
     */
    @Test
    @DisplayName("Internal Phone: Update To A Number Held By Another User Rejected With Conflict - [MEANT TO FAIL]")
    void updateInternalPhoneNumber_numberHeldByAnotherUser_returns409AndSavesNothing() throws Exception {
        // id 2 is the whole fixture: the lookup has to distinguish "someone else holds it" from
        // "the caller already holds it", and only the id separates those two
        User otherUser = mock(User.class);
        given(otherUser.getId()).willReturn(2L);
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(userRepository.findByPhoneNumber("+15712856947")).willReturn(Optional.of(otherUser));

        mockMvc.perform(put("/api/v1/internal/users/1/phone-number")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"+15712856947\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("That phone number is already registered"));

        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * The identity form re-submits every field on every save, so the user's own unchanged number
     * arrives here constantly. A plain "is this number taken" check would answer yes and make the
     * form impossible to save without also editing the phone field.
     */
    @Test
    @DisplayName("Internal Phone: Re-submitting The User's Own Current Number Succeeds - [MEANT TO PASS]")
    void updateInternalPhoneNumber_usersOwnUnchangedNumber_returns200AndSaves() throws Exception {
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(userRepository.findByPhoneNumber("+15551234567")).willReturn(Optional.of(mockUser));

        mockMvc.perform(put("/api/v1/internal/users/1/phone-number")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"+15551234567\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+15551234567"));

        verify(userRepository).save(mockUser);
    }

    /**
     * Same rule registration applies: the SMS provider only accepts E.164, so what the user typed
     * into the KYC form is converted before it is stored rather than saved as-is.
     */
    @Test
    @DisplayName("Internal Phone: Update Stores E.164 Regardless Of Typed Format - [MEANT TO PASS]")
    void updateInternalPhoneNumber_phoneTypedInLocalFormat_storesE164Number() throws Exception {
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(userRepository.findByPhoneNumber("+15712856947")).willReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/internal/users/1/phone-number")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"(571) 285-6947\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+15712856947"));

        // the response body could be normalised on the way out and still leave the typed value in
        // the row, so the setter call is what proves E.164 is what actually gets persisted
        verify(mockUser).setPhoneNumber("+15712856947");
        verify(userRepository).save(mockUser);
    }

    /**
     * Refused rather than stored: overwriting a working number with one that cannot be resolved
     * would leave the account unable to complete a 2FA login, with nothing to indicate why.
     */
    @Test
    @DisplayName("Internal Phone: Unresolvable Number Rejected And Nothing Saved - [MEANT TO FAIL]")
    void updateInternalPhoneNumber_numberThatCannotBeNormalized_returns400AndSavesNothing() throws Exception {
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));

        mockMvc.perform(put("/api/v1/internal/users/1/phone-number")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"285-6947\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Please enter a valid phone number, e.g. 571-285-6947 or +15712856947"));

        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * profile-service reads the number back from here rather than from its own row, so this is the
     * single value the KYC form displays and the one 2FA actually sends to.
     */
    @Test
    @DisplayName("Internal Phone: Lookup Returns The Number On File - [MEANT TO PASS]")
    void getInternalPhoneNumber_knownUser_returns200WithStoredNumber() throws Exception {
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));

        mockMvc.perform(get("/api/v1/internal/users/1/phone-number")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+15551234567"));
    }

    @Test
    @DisplayName("Internal Phone: Lookup For Unknown User Returns Not Found - [MEANT TO FAIL]")
    void getInternalPhoneNumber_unknownUser_returns404() throws Exception {
        given(userRepository.findById(404L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/internal/users/404/phone-number")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isNotFound());
    }

    // ==========================================
    // Internal Endpoint Shared-Secret Gate (InternalTokenFilter)
    // ==========================================

    // The whole /api/v1/internal/ prefix used to be reachable by anyone who could send it a packet -
    // the k8s ingress not routing the prefix was the only thing in the way. These pin the second
    // layer: the caller has to present the shared secret as well.

    /**
     * The PUT behind this prefix rewrites the number 2FA codes are delivered to, so an
     * unauthorized caller reaching the controller at all is an account takeover. The three
     * {@code never()} calls are what prove the request died in the filter, before anything read
     * or wrote the user row — a 401 alone could equally have come from the controller.
     */
    @Test
    @DisplayName("Internal Auth: Update Without Internal Token Rejected Before Reaching Repository - [MEANT TO FAIL]")
    void updateInternalPhoneNumber_missingInternalTokenHeader_returns401BeforeReachingRepository() throws Exception {
        mockMvc.perform(put("/api/v1/internal/users/1/phone-number")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"+15712856947\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).findByPhoneNumber(any(String.class));
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * The read is gated on the same terms as the write — the number itself is worth protecting,
     * since knowing where a victim's 2FA codes land is the first half of intercepting them.
     */
    @Test
    @DisplayName("Internal Auth: Lookup Without Internal Token Rejected - [MEANT TO FAIL]")
    void getInternalPhoneNumber_missingInternalTokenHeader_returns401BeforeReachingRepository() throws Exception {
        mockMvc.perform(get("/api/v1/internal/users/1/phone-number"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());

        verify(userRepository, never()).findById(any());
    }

    /**
     * A wrong secret is answered exactly like a missing one, and the body names neither the header
     * nor the property — a caller guessing at the scheme should learn nothing from the rejection.
     */
    @Test
    @DisplayName("Internal Auth: Wrong Internal Token Rejected Without Revealing The Scheme - [MEANT TO FAIL]")
    void updateInternalPhoneNumber_wrongInternalToken_returns401WithoutNamingHeaderOrProperty() throws Exception {
        String body = mockMvc.perform(put("/api/v1/internal/users/1/phone-number")
                .header(INTERNAL_TOKEN_HEADER, "not-the-real-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"+15712856947\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(INTERNAL_TOKEN_HEADER);
        assertThat(body).doesNotContain("internal-token");

        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * The gate is only a gate if the legitimate caller still gets through unchanged:
     * profile-service presents the secret and gets the same 200 and the same body it did before
     * any of this existed.
     */
    @Test
    @DisplayName("Internal Auth: Lookup With Correct Internal Token Returns The Number As Before - [MEANT TO PASS]")
    void getInternalPhoneNumber_correctInternalToken_returns200WithStoredNumber() throws Exception {
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));

        mockMvc.perform(get("/api/v1/internal/users/1/phone-number")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+15551234567"));
    }

    @Test
    @DisplayName("Internal Auth: Update With Correct Internal Token Still Saves The Number - [MEANT TO PASS]")
    void updateInternalPhoneNumber_correctInternalToken_returns200AndSaves() throws Exception {
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(userRepository.findByPhoneNumber("+15712856947")).willReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/internal/users/1/phone-number")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"+15712856947\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+15712856947"));

        verify(userRepository).save(mockUser);
    }

    /**
     * The filter has to be scoped to the internal prefix and nothing else — real customers never
     * send this header, so demanding it anywhere else would lock every one of them out of the app.
     * Two endpoints are exercised because a public one and a JWT-authenticated one take different
     * paths through the chain, and only the second reaches the filters behind
     * {@code InternalTokenFilter}.
     */
    @Test
    @DisplayName("Internal Auth: Customer Endpoints Still Work With No Internal Token Header - [MEANT TO PASS]")
    void registerAndLogout_noInternalTokenHeader_customerEndpointsStillSucceed() throws Exception {
        given(userRepository.existsByUsername("newuser")).willReturn(false);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"SecurePass123!\",\"phoneNumber\":\"+15551234567\","
                        + "\"email\":\"newuser@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        String fullAuthToken = jwtService.generateToken(mockUser, TokenType.FULL_AUTH);

        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + fullAuthToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"));
    }

    /**
     * The PRE_AUTH boundary the JWT filter enforces is untouched by the new filter sitting in
     * front of it — a half-authenticated token is still refused everything except 2FA
     * verification.
     */
    @Test
    @DisplayName("Internal Auth: PRE_AUTH Boundary Still Enforced With The Internal Filter In The Chain - [MEANT TO FAIL]")
    void logout_preAuthTokenWithInternalFilterInChain_returns403PartialAuthentication() throws Exception {
        String preAuthToken = jwtService.generateToken(mockUser, TokenType.PRE_AUTH);

        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + preAuthToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Partial authentication. 2FA verification required."));
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
}
