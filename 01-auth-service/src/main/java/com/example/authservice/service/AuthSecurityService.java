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

/**
 * Holds the secrets side of authentication: device fingerprints, 2FA code issue and verification,
 * session revocation, and the registration fan-out.
 *
 * <p>Every secret this class handles is stored as a SHA-256 hash and compared as a hash. Device
 * cookies, refresh tokens, and 2FA codes are all returned raw exactly once, at the moment they
 * are minted, and never recoverable afterwards.
 */
@Service
public class AuthSecurityService {

    private final RecognizedDeviceRepository deviceRepository;
    private final TwoFactorCodeRepository twoFactorCodeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BlacklistedTokenRepository blacklistedTokenRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${application.security.two-factor.code-ttl-seconds:180}")
    private int codeTtlSeconds;

    private static final long RESEND_COOLDOWN_SECONDS = 30;

    /**
     * Distinguishes the one success from the four distinct ways verifying a 2FA code can fail.
     *
     * <p>Exists so {@link #verifySms2fa} can return rather than throw. Verification runs in a
     * transaction that also deletes the code row, and throwing for the expired and locked cases
     * rolled back the very delete that was meant to burn the code, leaving dead rows in the table
     * and handing the caller a blanket {@code 500}. Each constant maps to a different HTTP status
     * and a different thing the client must do next: {@code INVALID} retry, {@code NO_CODE} and
     * {@code EXPIRED} resend, {@code LOCKED} restart the login.
     */
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

    /**
     * Reports whether a device cookie belongs to a device this user has already cleared 2FA on.
     *
     * <p>This is the check that decides whether a login skips 2FA, so a {@code false} is always
     * the safe answer: a {@code null} or blank cookie is treated as an unknown device rather than
     * as an error. The cookie is matched by hash, since only the hash was ever stored.
     *
     * @param userId must reference an existing user; an unknown id simply matches nothing
     * @param rawDeviceCookie the raw {@code Device-ID} cookie value, not its hash; {@code null}
     *     and blank are both normal and yield {@code false}
     * @return {@code true} only when this exact cookie is on file for this user
     */
    public boolean isDeviceRecognized(Long userId, String rawDeviceCookie) {
        if (rawDeviceCookie == null) return false;
        if (rawDeviceCookie.isBlank()) return false;
        String hashedCookie = hashString(rawDeviceCookie);
        return deviceRepository.findByUserIdAndDeviceHash(userId, hashedCookie).isPresent();
    }

    /**
     * Mints a new device identifier for a user who has just cleared 2FA and records its hash.
     *
     * <p>The raw identifier is returned exactly once, for the caller to set as the
     * {@code Device-ID} cookie; only its hash is stored, so it cannot be recovered afterwards.
     * Call this only after a code has actually been verified: a device registered here skips 2FA
     * on every subsequent login for as long as the cookie lives.
     *
     * @param userId must reference an existing user; the row is written without checking
     * @return the raw device identifier, never {@code null}
     */
    @Transactional
    public String registerNewDevice(Long userId) {
        String rawDeviceId = UUID.randomUUID().toString();
        String hashedId = hashString(rawDeviceId);
        deviceRepository.save(new RecognizedDevice(userId, hashedId));
        return rawDeviceId;
    }

    /**
     * Replaces any live 2FA code for this user with a fresh one and announces it for delivery.
     *
     * <p>Named for the step rather than the channel: this service mints the code and publishes
     * the event, and which transport notification-service picks is not its business. The raw
     * code never comes back, because returning it was how it once ended up in a login response
     * body; the caller gets only the lifetime it needs to count down from.
     *
     * <p>The store and the Kafka publish share one transaction, so a broker that will not accept
     * the event rolls the new code back rather than leaving a code in the table that nobody was
     * ever told. Note that this destroys the existing code row first, which is why
     * {@link #secondsUntilResendAllowed} has to be consulted before this method and not after.
     *
     * @param userId must reference an existing user
     * @param phoneNumber E.164 or {@code null}; carried on the event as the fallback contact
     *     rather than used here, since delivery is by email today
     * @param email the address the code is actually sent to; {@code null} leaves the message
     *     undeliverable, as auth-service is the only holder of this value
     * @return the code's lifetime in seconds, the same figure that reaches the notification text,
     *     so the on-screen countdown and the email agree
     * @throws RuntimeException when the event cannot be published, which rolls back the stored
     *     code
     */
    @Transactional
    public int trigger2fa(Long userId, String phoneNumber, String email) {
        String code = generateAndStoreCode(userId);
        publish2faEvent(userId, phoneNumber, email, code);
        return codeTtlSeconds;
    }

    /**
     * Reports how many seconds remain before this user may request another 2FA code.
     *
     * <p>Must be called <em>before</em> {@link #trigger2fa}, which deletes the very row this
     * reads and would therefore always report zero afterwards. The window is measured from the
     * existing code's creation time rather than from a separate rate-limit table, because there
     * is only ever one live code per user and that row already records when it was minted.
     *
     * <p>The cooldown is 30 seconds, far shorter than the code's own lifetime: long enough that
     * a held-down button cannot fan out a mailbox worth of codes, short enough that someone whose
     * first email never arrived is not left staring at a dead form.
     *
     * @param userId must reference an existing user; a user with no code on file has nothing to
     *     wait on and yields {@code 0}
     * @return seconds still to wait, {@code 0} when a resend may go immediately, and never more
     *     than the 30-second window even if the stored timestamp is in the future because of
     *     clock skew between instances
     */
    public int secondsUntilResendAllowed(Long userId) {
        return twoFactorCodeRepository.findByUserId(userId)
                .map(existing -> {
                    long elapsed = Duration.between(existing.getCreatedAt(), LocalDateTime.now()).getSeconds();
                    if (elapsed >= RESEND_COOLDOWN_SECONDS) {
                        return 0;
                    }
                    return (int) Math.min(RESEND_COOLDOWN_SECONDS, RESEND_COOLDOWN_SECONDS - elapsed);
                })
                .orElse(0);
    }

    private String generateAndStoreCode(Long userId) {
        twoFactorCodeRepository.deleteByUserId(userId);

        SecureRandom random = new SecureRandom();
        String code = String.format("%06d", random.nextInt(999999));

        twoFactorCodeRepository.save(new TwoFactorCode(userId, hashString(code), codeTtlSeconds));

        return code;
    }

    private void publish2faEvent(Long userId, String phoneNumber, String email, String code) {
        try {
            Map<String, String> event = new HashMap<>();
            event.put("action", "TWO_FA_REQUESTED");
            event.put("userId", userId.toString());
            event.put("phoneNumber", phoneNumber);
            event.put("email", email);
            event.put("code", code);
            event.put("expiresInSeconds", String.valueOf(codeTtlSeconds));

            kafkaTemplate.send("notification-events", objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish 2FA event to Kafka", e);
        }
    }

    /**
     * Checks a submitted 2FA code against the one on file and consumes the code either way.
     *
     * <p>A code is single-use: a correct one is deleted so it cannot be replayed, and an expired
     * or locked-out one is deleted so it cannot linger. Every path returns rather than throws,
     * precisely so those deletes commit; an exception here would roll back the cleanup it just
     * performed.
     *
     * <p>Three wrong attempts burn the code permanently. There is no way back from
     * {@code LOCKED} except a fresh login, since the row the resend endpoint would extend no
     * longer exists.
     *
     * @param userId must reference the user the {@code PRE_AUTH} token was issued for; the code
     *     is looked up by this id alone, so passing another user's id checks another user's code
     * @param providedCode the six digits as typed; must not be {@code null}, which faults rather
     *     than counting as a failed attempt, and a wrong value spends one of the three attempts
     * @return which of the five outcomes occurred, never {@code null}
     */
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

        twoFactorCodeRepository.delete(storedCode);
        return TwoFaResult.VALID;
    }

    /**
     * Announces a newly registered user so the other services can provision their own rows.
     *
     * <p>auth-service owns only credentials. profile-service and account-service each hold their
     * own slice of a user, contact and KYC data on one side and accounts on the other, and this
     * event is the only way either of them learns the user exists, so a user whose event is never
     * published has no profile and no account.
     *
     * <p>Not transactional and not retried: call it after the user row is committed, because a
     * publish failure here throws and the caller sees a failed registration for an account that
     * now exists.
     *
     * @param userId must reference the just-saved user
     * @param username the login name, the only place any other service can get one
     * @param phoneNumber E.164 or {@code null}
     * @param email a real, deliverable address; downstream services address balance summaries and
     *     transaction alerts to it
     * @throws RuntimeException when the event cannot be serialized or published
     */
    public void publishUserRegisteredEvent(Long userId, String username, String phoneNumber, String email) {
        try {
            Map<String, String> event = new HashMap<>();
            event.put("userId", String.valueOf(userId));
            event.put("username", username);
            event.put("phoneNumber", phoneNumber);
            event.put("email", email);

            kafkaTemplate.send("user-events", objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish user registered event to Kafka", e);
        }
    }

    /**
     * Revokes every refresh token a user holds and blacklists the access token they logged out with.
     *
     * <p>Both halves are needed and both happen in one transaction: revoking refresh tokens alone
     * would leave the current access token usable for the rest of its 15 minutes, and blacklisting
     * the access token alone would let the caller mint a new one from the refresh cookie. Because
     * the revocation is user-wide rather than session-wide, this signs the account out of every
     * device, not just the one that called.
     *
     * <p>The blacklist row is kept only until the token would have expired anyway; a scheduled
     * purge clears it after that, so the table stays proportional to live sessions.
     *
     * @param userId must reference the user the token belongs to; nothing cross-checks the two,
     *     so a mismatched pair revokes one user's sessions and blacklists another's token
     * @param jwtJti the {@code jti} claim of the access token being retired, the value the
     *     request filter looks up on every subsequent call
     * @param jwtExpiration that token's own expiry, interpreted in the JVM's default zone; a
     *     value in the past leaves the entry immediately eligible for purging
     */
    @Transactional
    public void logoutUserSession(Long userId, String jwtJti, Date jwtExpiration) {
        refreshTokenRepository.revokeAllUserTokens(userId);

        LocalDateTime expiresAt = LocalDateTime.ofInstant(jwtExpiration.toInstant(), ZoneId.systemDefault());
        blacklistedTokenRepository.save(new BlacklistedToken(jwtJti, expiresAt));
    }

    /**
     * Drops blacklist entries whose tokens have expired on their own.
     *
     * <p>Runs hourly rather than continuously because nothing depends on the timing: an entry
     * past its expiry is already harmless, since the filter that consults the list would reject
     * the expired token anyway. The schedule exists only to keep the table proportional to live
     * sessions instead of to every logout ever performed.
     *
     * <p>Runs on the scheduler's own thread on every instance, so in a multi-replica deployment
     * several replicas execute it at the same hour; the delete is idempotent, so that is
     * wasteful rather than wrong.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void purgeExpiredBlacklistTokens() {
        blacklistedTokenRepository.deleteAllExpiredTokensSince(LocalDateTime.now());
    }

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