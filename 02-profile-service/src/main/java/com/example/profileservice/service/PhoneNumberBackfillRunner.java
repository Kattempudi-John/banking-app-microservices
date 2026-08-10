package com.example.profileservice.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.profileservice.model.UserProfile;
import com.example.profileservice.repository.UserProfileRepository;
import com.example.profileservice.util.PhoneNumberNormalizer;

// Mirrors auth-service's runner of the same name, for this service's own copy of the phone number.
// Profiles provisioned from a "user-events" message before registration normalized its input hold
// whatever the user originally typed, so they're brought into the same E.164 shape here - otherwise
// the Profile page would keep displaying a number in a format the platform no longer accepts.
@Component
public class PhoneNumberBackfillRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(PhoneNumberBackfillRunner.class);

    private final UserProfileRepository userProfileRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;

    public PhoneNumberBackfillRunner(UserProfileRepository userProfileRepository,
                                      PhoneNumberNormalizer phoneNumberNormalizer) {
        this.userProfileRepository = userProfileRepository;
        this.phoneNumberNormalizer = phoneNumberNormalizer;
    }

    // Best-effort housekeeping on historical rows - never allowed to stop the service booting.
    @Override
    public void run(ApplicationArguments args) {
        try {
            backfillPhoneNumbers();
        } catch (RuntimeException e) {
            logger.error("Phone number backfill did not run. Existing profiles keep whatever format "
                    + "they were stored in; nothing else is affected.", e);
        }
    }

    @Transactional
    public void backfillPhoneNumbers() {
        List<UserProfile> updated = new ArrayList<>();
        List<Long> unfixable = new ArrayList<>();

        for (UserProfile profile : userProfileRepository.findAll()) {
            String stored = profile.getPhoneNumber();
            if (stored == null || stored.isBlank()) {
                continue;
            }

            String normalized = phoneNumberNormalizer.normalize(stored).orElse(null);

            if (normalized == null) {
                // Ambiguous input - left untouched rather than guessed at, and named in the log.
                unfixable.add(profile.getId());
                continue;
            }

            if (!normalized.equals(stored)) {
                profile.setPhoneNumber(normalized);
                updated.add(profile);
            }
        }

        if (!updated.isEmpty()) {
            userProfileRepository.saveAll(updated);
            logger.info("Normalized {} stored profile phone number(s) to E.164", updated.size());
        }

        if (!unfixable.isEmpty()) {
            logger.warn("{} profile(s) have a phone number that can't be normalized and were left unchanged "
                    + "(user ids: {})", unfixable.size(), unfixable);
        }
    }
}
