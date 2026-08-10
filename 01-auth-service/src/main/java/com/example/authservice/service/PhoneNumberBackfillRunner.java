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

// Registration normalizes phone numbers to E.164 from here on, but rows created before that was
// enforced hold whatever was typed - "571-285-6947" and the like. Those numbers are the destination
// for 2FA codes, and the SMS provider rejects anything that isn't E.164, so an un-normalized row is
// an account that can never finish logging in from a new device.
//
// Same shape as account-service's IbanBackfillRunner: idempotent, best-effort, and it reuses the
// single normalizer the registration path uses rather than reimplementing the rules in SQL.
@Component
public class PhoneNumberBackfillRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(PhoneNumberBackfillRunner.class);

    private final UserRepository userRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;

    public PhoneNumberBackfillRunner(UserRepository userRepository, PhoneNumberNormalizer phoneNumberNormalizer) {
        this.userRepository = userRepository;
        this.phoneNumberNormalizer = phoneNumberNormalizer;
    }

    // Wrapped so a failure can never stop the service booting - this is housekeeping on historical
    // rows, not part of serving any request. Logged at error level so a real failure stays visible.
    @Override
    public void run(ApplicationArguments args) {
        try {
            backfillPhoneNumbers();
        } catch (RuntimeException e) {
            logger.error("Phone number backfill did not run. Users whose stored number isn't in E.164 "
                    + "will not receive 2FA codes until this succeeds.", e);
        }
    }

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
                // Can't be resolved to one unambiguous number, and guessing would point 2FA codes at
                // somebody else's phone. Leave the row exactly as it is and name it in the log so it
                // can be corrected by hand.
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
