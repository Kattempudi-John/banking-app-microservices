package com.example.authservice.service;

import com.example.authservice.model.BlacklistedToken;
import com.example.authservice.model.RecognizedDevice;
import com.example.authservice.model.RefreshToken;
import com.example.authservice.model.TwoFactorCode;
import com.example.authservice.repository.BlacklistedTokenRepository;
import com.example.authservice.repository.RecognizedDeviceRepository;
import com.example.authservice.repository.RefreshTokenRepository;
import com.example.authservice.repository.TwoFactorCodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// @Service marks this as a spring managed bean in the business logic layer, functionally almost
// the same as @Component, just a more specific name so the intent of the class is clear at a glance
@Service
public class AuthSecurityService {

    private final RecognizedDeviceRepository deviceRepository;
    private final TwoFactorCodeRepository twoFactorCodeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BlacklistedTokenRepository blacklistedTokenRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // The one place the code's lifetime is decided. It sets expires_at on the row, it is what the
    // login/resend responses report as expires_in_seconds, and it rides on the Kafka event so the
    // email text matches the on-screen countdown. Inline :180 fallback so tests and a fresh
    // checkout need no configuration.
    @Value("${application.security.two-factor.code-ttl-seconds:180}")
    private int codeTtlSeconds;

    // How long a user has to wait before asking for another code. Deliberately much shorter than
    // the TTL: long enough that a held-down button can't fan out a mailbox worth of codes, short
    // enough that someone whose first email never arrived isn't stuck staring at a dead form.
    private static final long RESEND_COOLDOWN_SECONDS = 30;

    // Returned by verifySms2fa instead of a bare boolean. The method is @Transactional and used to
    // throw for the expired/locked cases, which rolled back the very delete that was supposed to
    // burn the code - so an expired code stayed in the table and a locked-out account never got
    // its row cleared. Returning a value lets the transaction commit and still tells the
    // controller which of the four failures it was, which is what picks the status code.
    public enum TwoFaResult { VALID, INVALID, EXPIRED, LOCKED, NO_CODE }

    public AuthSecurityService(RecognizedDeviceRepository deviceRepository,
                               TwoFactorCodeRepository twoFactorCodeRepository,
                               RefreshTokenRepository refreshTokenRepository,
                               BlacklistedTokenRepository blacklistedTokenRepository,
                               KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper) {
        this.deviceRepository = deviceRepository;
        this.twoFactorCodeRepository = twoFactorCodeRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.blacklistedTokenRepository = blacklistedTokenRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    // ==========================================
    // 1. Device Fingerprinting
    // ==========================================

    public boolean isDeviceRecognized(Long userId, String rawDeviceCookie) {
        if (rawDeviceCookie == null) return false;
        if (rawDeviceCookie.isBlank()) return false;
        String hashedCookie = hashString(rawDeviceCookie);
        return deviceRepository.findByUserIdAndDeviceHash(userId, hashedCookie).isPresent();
    }

    // @Transactional wraps this whole method in one database transaction, if anything after the
    // save throws, the save gets rolled back too, so the db never ends up in a half done state
    @Transactional
    public String registerNewDevice(Long userId) {
        String rawDeviceId = UUID.randomUUID().toString();
        String hashedId = hashString(rawDeviceId);
        deviceRepository.save(new RecognizedDevice(userId, hashedId));
        return rawDeviceId; // Return raw so the Controller can send it as a Set-Cookie header
    }

    // ==========================================
    // 2. 2FA Orchestration
    // ==========================================

    // Named for the step rather than the channel now that delivery has moved from SMS to email -
    // auth-service's job is only to mint the code and announce it, whichever transport
    // notification-service ends up picking is none of this method's business.
    // Returns the code's lifetime in seconds, never the code itself. The raw code leaving this
    // method was how it ended up on the login response body - the caller needs a number to count
    // down from, not the secret, so that is all it gets.
    @Transactional
    public int trigger2fa(Long userId, String phoneNumber, String email) {
        String code = generateAndStoreCode(userId);
        publish2faEvent(userId, phoneNumber, email, code);
        return codeTtlSeconds;
    }

    // How long the caller must wait before another code may be requested - 0 when it may go now.
    // Measured off the existing row's created_at rather than a separate rate-limit table, because
    // there is exactly one live code per user and that row already records when it was minted.
    // Read this BEFORE trigger2fa: the first thing it does is delete the row this looks at.
    public int secondsUntilResendAllowed(Long userId) {
        return twoFactorCodeRepository.findByUserId(userId)
                .map(existing -> {
                    long elapsed = Duration.between(existing.getCreatedAt(), LocalDateTime.now()).getSeconds();
                    if (elapsed >= RESEND_COOLDOWN_SECONDS) {
                        return 0;
                    }
                    // Clamped at the full cooldown so a created_at somehow sitting in the future
                    // (clock skew between app instances) can't hand back a retry-after larger than
                    // the window itself, which would strand the user for longer than 30 seconds.
                    return (int) Math.min(RESEND_COOLDOWN_SECONDS, RESEND_COOLDOWN_SECONDS - elapsed);
                })
                .orElse(0); // No code on file at all - nothing to wait on
    }

    private String generateAndStoreCode(Long userId) {
        // 1. Clear out any old codes stuck in the database
        twoFactorCodeRepository.deleteByUserId(userId);

        // 2. Generate a highly secure 6-digit random code
        SecureRandom random = new SecureRandom();
        String code = String.format("%06d", random.nextInt(999999));

        twoFactorCodeRepository.save(new TwoFactorCode(userId, hashString(code), codeTtlSeconds));

        return code;
    }

    private void publish2faEvent(Long userId, String phoneNumber, String email, String code) {
        // 4. Fire the Kafka Event
        try {
            Map<String, String> event = new HashMap<>();
            // Renamed from SMS_2FA_REQUESTED because the action is no longer tied to one transport -
            // notification-service reads this to decide it has a 2FA code to deliver, not how to send it.
            event.put("action", "TWO_FA_REQUESTED");
            event.put("userId", userId.toString()); // notification-service needs this to record who the code went to
            // Both contact details ride along. Email is what actually gets used today, but auth-service
            // owns the User row and is the only place either value lives, so dropping the number here
            // would mean nothing downstream could fall back to SMS without a call back into this service.
            event.put("phoneNumber", phoneNumber);
            event.put("email", email);
            event.put("code", code); // The notification service needs the raw code to put in the message
            // So the email can say how long the code lasts without hardcoding its own number. The
            // map is Map<String,String>, hence the toString - notification-service parses it back
            // and falls back to 180 if it is missing, so the two services can deploy in either order.
            event.put("expiresInSeconds", String.valueOf(codeTtlSeconds));

            kafkaTemplate.send("notification-events", objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            // Because of @Transactional, throwing an exception here instantly rolls back the DB save!
            throw new RuntimeException("Failed to publish 2FA event to Kafka", e);
        }
    }

    // Every exit is a return, never a throw. Under @Transactional an exception rolls the whole
    // method back, so the old expired/locked branches deleted the row and then immediately threw
    // the delete away again - the dead code stayed in the table and the caller got a blanket 500
    // instead of a status that told it what happened.
    @Transactional
    public TwoFaResult verifySms2fa(Long userId, String providedCode) {
        TwoFactorCode storedCode = twoFactorCodeRepository.findByUserId(userId).orElse(null);
        if (storedCode == null) {
            return TwoFaResult.NO_CODE;
        }

        if (storedCode.isExpired()) {
            twoFactorCodeRepository.delete(storedCode);
            return TwoFaResult.EXPIRED;
        }

        if (storedCode.getAttempts() >= 3) {
            twoFactorCodeRepository.delete(storedCode);
            return TwoFaResult.LOCKED;
        }

        if (!storedCode.getCodeHash().equals(hashString(providedCode))) {
            storedCode.incrementAttempts();
            twoFactorCodeRepository.save(storedCode);
            return TwoFaResult.INVALID;
        }

        // Success! Clean up the code so it cannot be reused.
        twoFactorCodeRepository.delete(storedCode);
        return TwoFaResult.VALID;
    }

    // ==========================================
    // 2b. Registration Provisioning Fan-out
    // ==========================================

    // profile-service and account-service each own their own slice of "user" data (KYC/contact
    // info, accounts) and provision it themselves by consuming this event - auth-service only
    // owns credentials, so this is the only way those other services learn a new user exists.
    public void publishUserRegisteredEvent(Long userId, String username, String phoneNumber, String email) {
        try {
            Map<String, String> event = new HashMap<>();
            event.put("userId", String.valueOf(userId));
            event.put("username", username);
            event.put("phoneNumber", phoneNumber);
            // profile-service stores this so notification-service has somewhere real to send balance
            // summaries and transaction alerts - before this, those were addressed to a fabricated
            // "user_<id>@bank.com" that could never receive anything.
            event.put("email", email);

            kafkaTemplate.send("user-events", objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish user registered event to Kafka", e);
        }
    }

    // ==========================================
    // 3. Session Revocation & Blacklisting
    // ==========================================

    @Transactional
    public void logoutUserSession(Long userId, String jwtJti, Date jwtExpiration) {
        // 1. Revoke the long-lived refresh tokens in the DB
        refreshTokenRepository.revokeAllUserTokens(userId);

        // 2. Add the current short-lived JWT to the blacklist so it cannot be reused
        LocalDateTime expiresAt = LocalDateTime.ofInstant(jwtExpiration.toInstant(), ZoneId.systemDefault());
        blacklistedTokenRepository.save(new BlacklistedToken(jwtJti, expiresAt));
    }

    // ==========================================
    // 4. Automated Maintenance
    // ==========================================

    // learned @Scheduled just needs spring's scheduling support turned on somewhere with
    // @EnableScheduling, then it runs this method automatically on its own background thread
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void purgeExpiredBlacklistTokens() {
        blacklistedTokenRepository.deleteAllExpiredTokensSince(LocalDateTime.now());
    }

    // ==========================================
    // Internal Cryptography Helpers
    // ==========================================

    private String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encodedHash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash string", e);
        }
    }
}