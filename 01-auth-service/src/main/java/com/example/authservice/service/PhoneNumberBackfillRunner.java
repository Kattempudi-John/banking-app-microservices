package com.example.authservice.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.authservice.model.User;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.util.PhoneNumberNormalizer;

/**
 * Rewrites phone numbers stored before E.164 normalization was enforced at registration.
 *
 * <p>Rows created earlier hold whatever was typed, {@code 571-285-6947} and the like. Those
 * numbers are where 2FA codes are sent and the SMS provider refuses anything that is not E.164,
 * so an un-normalized row is an account that can never finish logging in from a new device.
 *
 * <p>Idempotent and best-effort, the same shape as account-service's IBAN backfill: it runs on
 * every boot, rewrites only rows that actually differ, and reuses the one normalizer the
 * registration path uses rather than reimplementing the rules in SQL, so the two can never
 * disagree about what a number means.
 */
@Component
public class PhoneNumberBackfillRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(PhoneNumberBackfillRunner.class);

    private final UserRepository userRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;

    public PhoneNumberBackfillRunner(UserRepository userRepository, PhoneNumberNormalizer phoneNumberNormalizer) {
        this.userRepository = userRepository;
        this.phoneNumberNormalizer = phoneNumberNormalizer;
    }

    /**
     * Runs the backfill once at startup, swallowing any failure so the service still boots.
     *
     * <p>This is housekeeping on historical rows, not part of serving a request, and a database
     * that will not cooperate at boot is not a reason to take the whole service down. The failure
     * is logged at error level instead, because until it succeeds the affected users cannot
     * receive 2FA codes.
     *
     * @param args ignored; the backfill takes no parameters and always processes every user
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            backfillPhoneNumbers();
        } catch (RuntimeException e) {
            logger.error("Phone number backfill did not run. Users whose stored number isn't in E.164 "
                    + "will not receive 2FA codes until this succeeds.", e);
        }
    }

    /**
     * Normalizes every stored phone number that is not already in E.164, in a single transaction.
     *
     * <p>All rows are read and rewritten together so the table is never left half-converted by a
     * failure partway through; nothing here is incremental, so a rollback simply means the next
     * boot tries again from the same starting point.
     *
     * <p>A number that cannot be resolved to one unambiguous value is left exactly as it is and
     * named in a warning, never guessed at. Guessing would point that account's 2FA codes at
     * somebody else's phone, so the number stays broken until a human corrects it.
     */
    @Transactional
    public void backfillPhoneNumbers() {
        List<User> updated = new ArrayList<>();
        List<String> unfixable = new ArrayList<>();

        for (User user : userRepository.findAll()) {
            String stored = user.getPhoneNumber();
            if (stored == null || stored.isBlank()) {
                continue;
            }

            String normalized = phoneNumberNormalizer.normalize(stored).orElse(null);

            if (normalized == null) {
                unfixable.add(user.getUsername());
                continue;
            }

            if (!normalized.equals(stored)) {
                user.setPhoneNumber(normalized);
                updated.add(user);
            }
        }

        if (!updated.isEmpty()) {
            userRepository.saveAll(updated);
            logger.info("Normalized {} stored phone number(s) to E.164 so 2FA codes can be delivered", updated.size());
        }

        if (!unfixable.isEmpty()) {
            logger.warn("{} user(s) have a phone number that can't be normalized and were left unchanged - "
                    + "they cannot receive 2FA codes until it is corrected: {}", unfixable.size(), unfixable);
        }
    }
}
