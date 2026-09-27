package com.example.authservice.service;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
        name = "automation.test-support.enabled",
        havingValue = "true"
)
public class AutomationOtpStore {

    private final Map<Long, StoredOtp> otpStore = new ConcurrentHashMap<>();

    public void save(Long userId, String otp, int ttlSeconds) {
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);

        otpStore.put(
                userId,
                new StoredOtp(otp, expiresAt)
        );
    }

    public String get(Long userId) {
        StoredOtp storedOtp = otpStore.get(userId);

        if (storedOtp == null) {
            return null;
        }

        if (storedOtp.expiresAt().isBefore(Instant.now())) {
            otpStore.remove(userId, storedOtp);
            return null;
        }

        return storedOtp.code();
    }

    public void remove(Long userId) {
        otpStore.remove(userId);
    }

    private record StoredOtp(
            String code,
            Instant expiresAt
    ) {
    }

}
