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
import jakarta.servlet.http.Cookie;
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

    // this runs before every single test in the class to set up one shared fake user
    // mockito mock() gives me a fake user object instead of a real db backed one
    // then I stub out the getters it will need, username, id and phone number
    // last I stub the user details service so spring security can load this fake user by username
    // saves every test below from repeating this same setup over and over
    @BeforeEach
    void setUp() {
        mockUser = mock(User.class);
        given(mockUser.getUsername()).willReturn("johndoe");
        given(mockUser.getId()).willReturn(1L);
        given(mockUser.getPhoneNumber()).willReturn("+15551234567");

        given(userDetailsService.loadUserByUsername("johndoe")).willReturn(mockUser);
    }

    // checking that an unknown device cookie gets correctly reported as not recognized
    // stub the device repository so looking up this user id with any hash string returns nothing
    // call isdevicerecognized directly on the service with a made up cookie value
    // since the repo came back empty the method should report false
    // and verify the repository method actually got called with those arguments
    @Test
    @DisplayName("Block 1: Device Repository Query Returns Empty for Unrecognized Cookie - [MEANT TO FAIL]")
    void testBlock1_UnrecognizedDevice_ReturnsFalse() {
        given(deviceRepository.findByUserIdAndDeviceHash(eq(1L), any(String.class)))
                .willReturn(Optional.empty());

        boolean recognized = authSecurityService.isDeviceRecognized(1L, "invalid-device-cookie-123");

        assertThat(recognized).isFalse();
        verify(deviceRepository).findByUserIdAndDeviceHash(eq(1L), any(String.class));
    }

    // same idea as the last test but flipped, this time the device should come back recognized
    // stub the repository to return an actual recognizeddevice record for this user and hash
    // call isdevicerecognized with a cookie value that is supposed to match that record
    // this time it should come back true since the repo actually had a match
    // and confirm the repository lookup was invoked with the right arguments
    @Test
    @DisplayName("Block 2: Device Repository Query Returns Match for Valid Cookie - [MEANT TO PASS]")
    void testBlock2_RecognizedDevice_ReturnsTrue() {
        given(deviceRepository.findByUserIdAndDeviceHash(eq(1L), any(String.class)))
                .willReturn(Optional.of(new RecognizedDevice(1L, "hashed-cookie")));

        boolean recognized = authSecurityService.isDeviceRecognized(1L, "valid-device-cookie-123");

        assertThat(recognized).isTrue();
        verify(deviceRepository).findByUserIdAndDeviceHash(eq(1L), any(String.class));
    }

    // full end to end style test hitting the real login endpoint through mockmvc
    // mock out the authentication manager so it succeeds and returns our fake user as the principal
    // also stub the device repository to say this device has never been seen before
    // post a real json body to /api/v1/auth/login the same way an actual client would
    // expect a 202 accepted status back instead of a normal 200 since 2fa still has to happen
    // and expect the response body to say 2fa_required and to include a pre auth token
    @Test
    @DisplayName("Final Block: E2E Login Unrecognized Device Triggers 2FA & Issues Pre-Auth Token - [MEANT TO PASS]")
    void testFinalAC_LoginUnrecognizedDevice_Requires2FA() throws Exception {
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
                .andExpect(jsonPath("$.pre_auth_token").exists());
    }

    // testing that kicking off sms 2fa actually does three things in one call
    // call triggersms2fa directly with a user id and a phone number
    // first it should delete any old 2fa code that is still sitting around for that user
    // then it should save a brand new twofactorcode row for the fresh code
    // and finally it should publish a message out to kafka on the notification events topic
    @Test
    @DisplayName("Block 1: Trigger SMS 2FA Clears Old Codes via Repository and Publishes to Kafka - [MEANT TO PASS]")
    void testBlock1_TriggerSms2fa_DeletesOldCodeAndPublishesKafka() {
        authSecurityService.triggerSms2fa(1L, "+15551234567");

        verify(twoFactorCodeRepository).deleteByUserId(1L);
        verify(twoFactorCodeRepository).save(any(TwoFactorCode.class));
        verify(kafkaTemplate).send(eq("notification-events"), any(String.class));
    }

    // end to end test for successfully verifying an sms 2fa code
    // generate a real pre auth jwt for the mock user using the actual jwtservice, not a fake one
    // stub the repository to return a valid twofactorcode that matches what gets submitted below
    // post the code to verify-2fa/sms using that pre auth token as the bearer header
    // expect status ok, a success field, an access token, and a set-cookie header for the device
    // last, make sure the code that just got used is actually deleted so it cannot be replayed
    @Test
    @DisplayName("Final Block: E2E Verify SMS 2FA Success Returns Session JWT & Device Cookie - [MEANT TO PASS]")
    void testFinalAC_VerifySms2fa_Success() throws Exception {
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

        verify(twoFactorCodeRepository).delete(validCode);
    }

    // checking that a pre auth token by itself cannot get into a fully protected endpoint
    // generate a pre auth token, this is the partial token issued before 2fa is completed
    // hit the logout endpoint using that pre auth token as the bearer token
    // it should get blocked with a forbidden status
    // and the error message should explain that full 2fa verification is still required
    @Test
    @DisplayName("Block 1: PRE_AUTH Token Restricted from Accessing Protected Endpoints - [MEANT TO FAIL]")
    void testBlock1_PreAuthToken_DeniedProtectedAccess() throws Exception {
        String preAuthToken = jwtService.generateToken(mockUser, TokenType.PRE_AUTH);

        mockMvc.perform(get("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + preAuthToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Partial authentication. 2FA verification required."));
    }

    // checking a fully authenticated token works the way it is supposed to end to end
    // generate a full auth token for the mock user, this is the real post 2fa token
    // confirm the token type that comes back out is full_auth and the username matches
    // then actually use that token to call the logout endpoint
    // expect a 200 ok back along with a logged out successfully message
    @Test
    @DisplayName("Final Block: FULL_AUTH Token Expiration and Authorization Check - [MEANT TO PASS]")
    void testFinalAC_FullAuthToken_AccessAllowed() throws Exception {
        String fullAuthToken = jwtService.generateToken(mockUser, TokenType.FULL_AUTH);

        assertThat(jwtService.extractTokenType(fullAuthToken)).isEqualTo(TokenType.FULL_AUTH);
        assertThat(jwtService.extractUsername(fullAuthToken)).isEqualTo("johndoe");

        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + fullAuthToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"));
    }

    // making sure the refresh endpoint fails cleanly when there is no cookie at all
    // call /api/v1/auth/refresh with no refresh token cookie attached to the request
    // expect a 401 unauthorized status back
    // and the error message should say the refresh token is missing
    @Test
    @DisplayName("Block 1: Refresh Session Fails When Cookie Missing - [MEANT TO FAIL]")
    void testBlock1_RefreshSession_MissingCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Refresh token missing"));
    }

    // making sure a revoked refresh token cannot be used to pull a new session
    // build a raw refresh token string and hash it the same way the real service would
    // create a refreshtoken entity with that hash and immediately call revoke() on it
    // stub the repository so looking up that hash returns this already revoked token
    // hit the refresh endpoint with the raw token attached as a cookie
    // expect 401 unauthorized with an error message saying the token is expired or revoked
    @Test
    @DisplayName("Block 2: Refresh Session Fails When Token Revoked in Database - [MEANT TO FAIL]")
    void testBlock2_RefreshSession_RevokedToken() throws Exception {
        String rawRefreshToken = "raw-refresh-token-uuid-123";
        String hashedToken = hashString(rawRefreshToken);

        RefreshToken revokedToken = new RefreshToken(1L, hashedToken);
        revokedToken.revoke();

        given(refreshTokenRepository.findByTokenHash(hashedToken)).willReturn(Optional.of(revokedToken));

        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(new Cookie("Refresh-Token", rawRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Refresh token expired or revoked"));
    }

    // happy path test for refreshing a session using a still valid refresh token
    // build a raw token and its hashed form the same way the service does internally
    // stub the repository to return an active, non revoked token for that hash
    // hit the refresh endpoint passing the raw token back as a cookie
    // expect status ok and a brand new access token sitting in the response body
    @Test
    @DisplayName("Final Block: Valid Refresh Token Issues Brand New FULL_AUTH Access Token - [MEANT TO PASS]")
    void testFinalAC_RefreshSession_Success() throws Exception {
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

    // testing that logging out actually cleans up both refresh tokens and the jwt itself
    // make up a jti string and an expiration date about fifteen minutes out
    // call logoutusersession directly with the user id, jti and expiration
    // verify it revoked every one of this user's refresh tokens
    // and verify it saved a new blacklistedtoken row for the jti so it cannot be reused
    @Test
    @DisplayName("Block 1: Logout User Revokes Refresh Tokens and Blacklists JTI - [MEANT TO PASS]")
    void testBlock1_LogoutSession_RevokesAndBlacklists() {
        String jti = "test-jwt-uuid-jti-1001";
        Date expiresAt = new Date(System.currentTimeMillis() + 900000);

        authSecurityService.logoutUserSession(1L, jti, expiresAt);

        verify(refreshTokenRepository).revokeAllUserTokens(1L);
        verify(blacklistedTokenRepository).save(any(BlacklistedToken.class));
    }

    // testing the scheduled cleanup job for the jwt blacklist table
    // call purgeexpiredblacklisttokens directly the same way the scheduler would
    // verify it calls deleteallexpiredtokenssince on the repository
    // passing some localdatetime as the cutoff, the exact instant does not matter here
    @Test
    @DisplayName("Block 2: Scheduled Purge Executes Expired Blacklist Token Delete Query - [MEANT TO PASS]")
    void testBlock2_PurgeExpiredBlacklistTokens() {
        authSecurityService.purgeExpiredBlacklistTokens();

        verify(blacklistedTokenRepository).deleteAllExpiredTokensSince(any(LocalDateTime.class));
    }

    // end to end test making sure a blacklisted jwt gets rejected by the security filter
    // generate a real full auth token then pull its jti back out of it
    // stub the blacklist repository so existsbyid for that jti returns true
    // hit the logout endpoint using that token as the bearer header
    // expect a 401 unauthorized with a message saying the token has been revoked
    // and confirm the filter actually checked the blacklist repository for that jti
    @Test
    @DisplayName("Final Block: Subsequent Request Using Blacklisted JTI Denied By JwtAuthenticationFilter - [MEANT TO FAIL]")
    void testFinalAC_BlacklistedJwt_DeniedByFilter() throws Exception {
        String fullAuthToken = jwtService.generateToken(mockUser, TokenType.FULL_AUTH);
        String jti = jwtService.extractJti(fullAuthToken);

        given(blacklistedTokenRepository.existsById(jti)).willReturn(true);

        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + fullAuthToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("{\"error\": \"Token has been revoked. Please log in again.\"}"));

        verify(blacklistedTokenRepository).existsById(jti);
    }

    // ==========================================
    // Registration
    // ==========================================

    @Test
    @DisplayName("Register: New Username Creates Account With Hashed Password - [MEANT TO PASS]")
    void testRegister_NewUsername_CreatesAccount() throws Exception {
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
        assertThat(passwordEncoder.matches("SecurePass123!", savedUser.getValue().getPassword())).isTrue();
        // notification-service delivers balance summaries and alerts here - a user saved without one
        // can't be emailed at all
        assertThat(savedUser.getValue().getEmail()).isEqualTo("newuser@example.com");
    }

    // confirms registration publishes a UserRegistered event so profile-service/account-service
    // can provision their own initial rows for this user - stubs save() to mimic Hibernate
    // populating the generated id on the passed-in entity, since a plain mock wouldn't do that
    @Test
    @DisplayName("Register: New Username Publishes UserRegistered Event To Kafka - [MEANT TO PASS]")
    void testRegister_NewUsername_PublishesUserRegisteredEvent() throws Exception {
        given(userRepository.existsByUsername("newuser")).willReturn(false);
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
    void testRegister_DuplicateUsername_Rejected() throws Exception {
        given(userRepository.existsByUsername("johndoe")).willReturn(true);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"johndoe\",\"password\":\"SecurePass123!\",\"email\":\"johndoe@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Username is already taken"));

        verify(kafkaTemplate, never()).send(eq("user-events"), any(String.class));
    }

    @Test
    @DisplayName("Register: Missing Username Or Password Rejected - [MEANT TO FAIL]")
    void testRegister_MissingFields_Rejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(kafkaTemplate, never()).send(eq("user-events"), any(String.class));
    }

    @Test
    @DisplayName("Register: Short Password Rejected - [MEANT TO FAIL]")
    void testRegister_ShortPassword_Rejected() throws Exception {
        given(userRepository.existsByUsername("newuser")).willReturn(false);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // an account with no address on file can never receive a balance summary or a transaction alert,
    // so registration insists on one even though the column itself is nullable for older rows
    @Test
    @DisplayName("Register: Missing Email Rejected - [MEANT TO FAIL]")
    void testRegister_MissingEmail_Rejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"SecurePass123!\",\"phoneNumber\":\"+15551234567\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Email is required"));

        verify(kafkaTemplate, never()).send(eq("user-events"), any(String.class));
    }

    // catches the obvious typo before it becomes an undeliverable address sitting in the database
    @Test
    @DisplayName("Register: Malformed Email Rejected - [MEANT TO FAIL]")
    void testRegister_MalformedEmail_Rejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"SecurePass123!\",\"email\":\"not-an-address\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Please enter a valid email address"));

        verify(kafkaTemplate, never()).send(eq("user-events"), any(String.class));
    }

    // a number stored the way a person types it is silently undeliverable - the SMS provider only
    // accepts E.164 - so registration converts it rather than storing what was typed
    @Test
    @DisplayName("Register: Phone Number Stored In E.164 Regardless Of Typed Format - [MEANT TO PASS]")
    void testRegister_NormalizesPhoneNumber() throws Exception {
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

    // refused rather than stored as-is: a number that can't be resolved would produce an account
    // that can never complete a 2FA login, with nothing to indicate why
    @Test
    @DisplayName("Register: Unresolvable Phone Number Rejected - [MEANT TO FAIL]")
    void testRegister_InvalidPhoneNumber_Rejected() throws Exception {
        given(userRepository.existsByUsername("newuser")).willReturn(false);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"SecurePass123!\",\"phoneNumber\":\"285-6947\","
                        + "\"email\":\"newuser@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(kafkaTemplate, never()).send(eq("user-events"), any(String.class));
    }

    // the email column is unique, so this has to be caught up front rather than surfacing as a
    // constraint violation from the insert
    @Test
    @DisplayName("Register: Duplicate Email Rejected With Conflict - [MEANT TO FAIL]")
    void testRegister_DuplicateEmail_Rejected() throws Exception {
        given(userRepository.existsByUsername("newuser")).willReturn(false);
        given(userRepository.existsByEmail("taken@example.com")).willReturn(true);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"SecurePass123!\",\"email\":\"taken@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("That email is already registered"));

        verify(kafkaTemplate, never()).send(eq("user-events"), any(String.class));
    }

    // the number is where that account's 2FA codes get sent, so a second account on the same phone
    // would let whoever holds it complete either login
    @Test
    @DisplayName("Register: Duplicate Phone Number Rejected With Conflict - [MEANT TO FAIL]")
    void testRegister_DuplicatePhoneNumber_Rejected() throws Exception {
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

    // the duplicate check runs on the normalized number, so the same phone typed in a different
    // shape is still caught - checking the raw string would let this one through
    @Test
    @DisplayName("Register: Duplicate Phone In A Different Format Still Rejected - [MEANT TO FAIL]")
    void testRegister_DuplicatePhoneDifferentFormat_Rejected() throws Exception {
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

    // the number is the destination for 2FA codes, so letting a second account claim one already in
    // use would hand whoever holds the phone a way into the first account's login - exactly what the
    // register endpoint already refuses, now refused on the update path too
    @Test
    @DisplayName("Internal Phone: Update To A Number Held By Another User Rejected With Conflict - [MEANT TO FAIL]")
    void testInternalPhone_NumberHeldByAnotherUser_Rejected() throws Exception {
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

    // the identity form re-submits every field on every save, so the user's own unchanged number
    // arrives here constantly - a plain "is this number taken" check would answer yes and make the
    // form impossible to save without also editing the phone field
    @Test
    @DisplayName("Internal Phone: Re-submitting The User's Own Current Number Succeeds - [MEANT TO PASS]")
    void testInternalPhone_OwnUnchangedNumber_Accepted() throws Exception {
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

    // same rule registration applies: the SMS provider only accepts E.164, so what the user typed
    // into the KYC form is converted before it is stored rather than saved as-is
    @Test
    @DisplayName("Internal Phone: Update Stores E.164 Regardless Of Typed Format - [MEANT TO PASS]")
    void testInternalPhone_NormalizesBeforeStoring() throws Exception {
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(userRepository.findByPhoneNumber("+15712856947")).willReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/internal/users/1/phone-number")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"(571) 285-6947\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+15712856947"));

        verify(mockUser).setPhoneNumber("+15712856947");
        verify(userRepository).save(mockUser);
    }

    // refused rather than stored: overwriting a working number with one that can't be resolved
    // would leave the account unable to complete a 2FA login, with nothing to indicate why
    @Test
    @DisplayName("Internal Phone: Unresolvable Number Rejected And Nothing Saved - [MEANT TO FAIL]")
    void testInternalPhone_UnresolvableNumber_Rejected() throws Exception {
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

    // profile-service reads the number back from here rather than from its own row, so this is the
    // single value the KYC form displays and the one 2FA actually sends to
    @Test
    @DisplayName("Internal Phone: Lookup Returns The Number On File - [MEANT TO PASS]")
    void testInternalPhone_LookupReturnsStoredNumber() throws Exception {
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));

        mockMvc.perform(get("/api/v1/internal/users/1/phone-number")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+15551234567"));
    }

    @Test
    @DisplayName("Internal Phone: Lookup For Unknown User Returns Not Found - [MEANT TO FAIL]")
    void testInternalPhone_LookupUnknownUser_NotFound() throws Exception {
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

    // the PUT behind this prefix rewrites the number 2FA codes are delivered to, so an unauthorized
    // caller reaching the controller at all is an account takeover - the request has to be turned
    // away in the filter, before anything reads or writes the user row
    @Test
    @DisplayName("Internal Auth: Update Without Internal Token Rejected Before Reaching Repository - [MEANT TO FAIL]")
    void testInternalAuth_MissingToken_RejectedAndRepositoryUntouched() throws Exception {
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

    // the read is gated on the same terms as the write - the number itself is worth protecting,
    // since knowing where a victim's 2FA codes land is the first half of intercepting them
    @Test
    @DisplayName("Internal Auth: Lookup Without Internal Token Rejected - [MEANT TO FAIL]")
    void testInternalAuth_MissingTokenOnLookup_Rejected() throws Exception {
        mockMvc.perform(get("/api/v1/internal/users/1/phone-number"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());

        verify(userRepository, never()).findById(any());
    }

    // a wrong secret is answered exactly like a missing one, and the body names neither the header
    // nor the property - a caller guessing at the scheme should learn nothing from the rejection
    @Test
    @DisplayName("Internal Auth: Wrong Internal Token Rejected Without Revealing The Scheme - [MEANT TO FAIL]")
    void testInternalAuth_WrongToken_Rejected() throws Exception {
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

    // the gate is only a gate if the legitimate caller still gets through unchanged - profile-service
    // presents the secret and gets the same 200 and the same body it did before any of this existed
    @Test
    @DisplayName("Internal Auth: Lookup With Correct Internal Token Returns The Number As Before - [MEANT TO PASS]")
    void testInternalAuth_ValidToken_LookupSucceeds() throws Exception {
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));

        mockMvc.perform(get("/api/v1/internal/users/1/phone-number")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+15551234567"));
    }

    @Test
    @DisplayName("Internal Auth: Update With Correct Internal Token Still Saves The Number - [MEANT TO PASS]")
    void testInternalAuth_ValidToken_UpdateSucceeds() throws Exception {
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

    // the filter has to be scoped to the internal prefix and nothing else - real customers never send
    // this header, so demanding it anywhere else would lock every one of them out of the app. Covers
    // both a public endpoint and a JWT-authenticated one, since they take different paths through
    // the chain.
    @Test
    @DisplayName("Internal Auth: Customer Endpoints Still Work With No Internal Token Header - [MEANT TO PASS]")
    void testInternalAuth_CustomerEndpointsUnaffected() throws Exception {
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

    // and the PRE_AUTH boundary the JWT filter enforces is untouched by the new filter sitting in
    // front of it - a half-authenticated token is still refused everything except 2FA verification
    @Test
    @DisplayName("Internal Auth: PRE_AUTH Boundary Still Enforced With The Internal Filter In The Chain - [MEANT TO FAIL]")
    void testInternalAuth_PreAuthBoundaryStillEnforced() throws Exception {
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