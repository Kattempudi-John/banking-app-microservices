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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /*
     * Optional bean.
     *
     * AutomationOtpStore only exists when:
     * automation.test-support.enabled=true
     *
     * In production, where the property is false, getIfAvailable()
     * simply returns null and normal authentication continues.
     */
    private final ObjectProvider<AutomationOtpStore> automationOtpStoreProvider;

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
    public enum TwoFaResult {
        VALID,
        INVALID,
        EXPIRED,
        LOCKED,
        NO_CODE
    }

    public AuthSecurityService(
            RecognizedDeviceRepository deviceRepository,
            TwoFactorCodeRepository twoFactorCodeRepository,
            RefreshTokenRepository refreshTokenRepository,
            BlacklistedTokenRepository blacklistedTokenRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<AutomationOtpStore> automationOtpStoreProvider
    ) {
        this.deviceRepository = deviceRepository;
        this.twoFactorCodeRepository = twoFactorCodeRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.blacklistedTokenRepository = blacklistedTokenRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.automationOtpStoreProvider = automationOtpStoreProvider;
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
     *                        and blank are both normal and yield {@code false}
     * @return {@code true} only when this exact cookie is on file for this user
     */
    public boolean isDeviceRecognized(Long userId, String rawDeviceCookie) {
        if (rawDeviceCookie == null) {
            return false;
        }

        if (rawDeviceCookie.isBlank()) {
            return false;
        }

        String hashedCookie = hashString(rawDeviceCookie);

        return deviceRepository
                .findByUserIdAndDeviceHash(userId, hashedCookie)
                .isPresent();
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

        deviceRepository.save(
                new RecognizedDevice(userId, hashedId)
        );

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
     * <p>The automation OTP copy is written only after this transaction successfully commits.
     * This prevents a stale automation OTP from existing when the database transaction rolls back.
     *
     * @param userId must reference an existing user
     * @param phoneNumber E.164 or {@code null}
     * @param email email destination
     * @return OTP lifetime in seconds
     */
    @Transactional
    public int trigger2fa(
            Long userId,
            String phoneNumber,
            String email
    ) {
        String code = generateAndStoreCode(userId);

        publish2faEvent(
                userId,
                phoneNumber,
                email,
                code
        );

        /*
         * Save raw OTP for automation only AFTER transaction commit.
         *
         * If Kafka or the transaction fails, nothing is written to
         * AutomationOtpStore.
         */
        afterTransactionCommit(() -> {
            AutomationOtpStore automationOtpStore =
                    automationOtpStoreProvider.getIfAvailable();

            if (automationOtpStore != null) {
                automationOtpStore.save(
                        userId,
                        code,
                        codeTtlSeconds
                );
            }
        });

        return codeTtlSeconds;
    }

    /**
     * Reports how many seconds remain before this user may request another 2FA code.
     *
     * @param userId user id
     * @return remaining cooldown seconds
     */
    public int secondsUntilResendAllowed(Long userId) {
        return twoFactorCodeRepository
                .findByUserId(userId)
                .map(existing -> {

                    long elapsed = Duration.between(
                            existing.getCreatedAt(),
                            LocalDateTime.now()
                    ).getSeconds();

                    if (elapsed >= RESEND_COOLDOWN_SECONDS) {
                        return 0;
                    }

                    return (int) Math.min(
                            RESEND_COOLDOWN_SECONDS,
                            RESEND_COOLDOWN_SECONDS - elapsed
                    );
                })
                .orElse(0);
    }

    /**
     * Generates a fresh six-digit OTP and stores only its hash in the database.
     *
     * Any previous OTP for the user is replaced.
     */
    private String generateAndStoreCode(Long userId) {

        /*
         * Remove previous database OTP first.
         */
        twoFactorCodeRepository.deleteByUserId(userId);

        SecureRandom random = new SecureRandom();

        /*
         * Generates values from:
         * 000000 to 999999
         */
        String code = String.format(
                "%06d",
                random.nextInt(1_000_000)
        );

        twoFactorCodeRepository.save(
                new TwoFactorCode(
                        userId,
                        hashString(code),
                        codeTtlSeconds
                )
        );

        return code;
    }

    private void publish2faEvent(
            Long userId,
            String phoneNumber,
            String email,
            String code
    ) {
        try {
            Map<String, String> event = new HashMap<>();

            event.put(
                    "action",
                    "TWO_FA_REQUESTED"
            );

            event.put(
                    "userId",
                    userId.toString()
            );

            event.put(
                    "phoneNumber",
                    phoneNumber
            );

            event.put(
                    "email",
                    email
            );

            event.put(
                    "code",
                    code
            );

            event.put(
                    "expiresInSeconds",
                    String.valueOf(codeTtlSeconds)
            );

            kafkaTemplate.send(
                    "notification-events",
                    objectMapper.writeValueAsString(event)
            );

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to publish 2FA event to Kafka",
                    e
            );
        }
    }

    /**
     * Checks a submitted 2FA code against the one on file.
     *
     * Valid, expired and locked codes are removed from both:
     *
     * - database
     * - LOCAL/QA automation store
     *
     * The automation store removal occurs after the transaction commits.
     *
     * @param userId user id taken from PRE_AUTH authentication
     * @param providedCode OTP supplied by the client
     * @return verification result
     */
    @Transactional
    public TwoFaResult verifySms2fa(
            Long userId,
            String providedCode
    ) {

        TwoFactorCode storedCode =
                twoFactorCodeRepository
                        .findByUserId(userId)
                        .orElse(null);

        /*
         * Database is the source of truth.
         *
         * If there is no DB OTP, make sure an automation OTP
         * cannot remain stale.
         */
        if (storedCode == null) {

            removeAutomationOtpAfterCommit(userId);

            return TwoFaResult.NO_CODE;
        }

        /*
         * Expired OTP:
         * remove DB row and automation raw OTP.
         */
        if (storedCode.isExpired()) {

            twoFactorCodeRepository.delete(storedCode);

            removeAutomationOtpAfterCommit(userId);

            return TwoFaResult.EXPIRED;
        }

        /*
         * Defensive check in case a row already has >= 3 attempts.
         */
        if (storedCode.getAttempts() >= 3) {

            twoFactorCodeRepository.delete(storedCode);

            removeAutomationOtpAfterCommit(userId);

            return TwoFaResult.LOCKED;
        }

        /*
         * Invalid OTP.
         */
        if (!storedCode
                .getCodeHash()
                .equals(hashString(providedCode))) {

            storedCode.incrementAttempts();

            /*
             * Third failed attempt immediately burns the OTP.
             *
             * Previous code saved attempts=3 and only locked/deleted
             * it on the NEXT request.
             */
            if (storedCode.getAttempts() >= 3) {

                twoFactorCodeRepository.delete(storedCode);

                removeAutomationOtpAfterCommit(userId);

                return TwoFaResult.LOCKED;
            }

            twoFactorCodeRepository.save(storedCode);

            return TwoFaResult.INVALID;
        }

        /*
         * Correct OTP:
         * consume it immediately.
         */
        twoFactorCodeRepository.delete(storedCode);

        removeAutomationOtpAfterCommit(userId);

        return TwoFaResult.VALID;
    }

    /**
     * Announces a newly registered user so the other services can provision their own rows.
     *
     * @param userId user id
     * @param username username
     * @param phoneNumber phone number
     * @param email email
     */
    public void publishUserRegisteredEvent(
            Long userId,
            String username,
            String phoneNumber,
            String email
    ) {
        try {
            Map<String, String> event = new HashMap<>();

            event.put(
                    "userId",
                    String.valueOf(userId)
            );

            event.put(
                    "username",
                    username
            );

            event.put(
                    "phoneNumber",
                    phoneNumber
            );

            event.put(
                    "email",
                    email
            );

            kafkaTemplate.send(
                    "user-events",
                    objectMapper.writeValueAsString(event)
            );

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to publish user registered event to Kafka",
                    e
            );
        }
    }

    /**
     * Revokes every refresh token a user holds and blacklists
     * the access token used for logout.
     *
     * @param userId user id
     * @param jwtJti JWT id
     * @param jwtExpiration JWT expiry
     */
    @Transactional
    public void logoutUserSession(
            Long userId,
            String jwtJti,
            Date jwtExpiration
    ) {

        refreshTokenRepository
                .revokeAllUserTokens(userId);

        LocalDateTime expiresAt =
                LocalDateTime.ofInstant(
                        jwtExpiration.toInstant(),
                        ZoneId.systemDefault()
                );

        blacklistedTokenRepository.save(
                new BlacklistedToken(
                        jwtJti,
                        expiresAt
                )
        );
    }

    /**
     * Drops blacklist entries whose tokens have already expired.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void purgeExpiredBlacklistTokens() {

        blacklistedTokenRepository
                .deleteAllExpiredTokensSince(
                        LocalDateTime.now()
                );
    }

    /**
     * Removes LOCAL/QA raw OTP only after the surrounding database
     * transaction has successfully committed.
     */
    private void removeAutomationOtpAfterCommit(Long userId) {

        afterTransactionCommit(() -> {

            AutomationOtpStore automationOtpStore =
                    automationOtpStoreProvider.getIfAvailable();

            if (automationOtpStore != null) {
                automationOtpStore.remove(userId);
            }
        });
    }

    /**
     * Executes an action only after transaction commit.
     *
     * If called without an active transaction, executes immediately.
     */
    private void afterTransactionCommit(Runnable action) {

        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {

            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {

                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    }
            );

            return;
        }

        action.run();
    }

    /**
     * Hashes authentication secrets before persistence/comparison.
     */
    private String hashString(String input) {

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] encodedHash =
                    digest.digest(
                            input.getBytes(StandardCharsets.UTF_8)
                    );

            return Base64
                    .getEncoder()
                    .encodeToString(encodedHash);

        } catch (NoSuchAlgorithmException e) {

            throw new RuntimeException(
                    "Failed to hash string",
                    e
            );
        }
    }
}