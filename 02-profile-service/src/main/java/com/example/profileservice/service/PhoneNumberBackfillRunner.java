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

/**
 * Rewrites historical profile phone numbers into E.164 form once at startup.
 *
 * <p>Profiles provisioned from a {@code user-events} message before registration normalized its
 * input hold whatever the user originally typed, so the Profile page would keep displaying numbers
 * in a format the platform no longer accepts. Mirrors auth-service's runner of the same name, for
 * this service's own copy of the field.
 */
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

    /**
     * Runs the backfill during startup, logging rather than propagating any failure.
     *
     * <p>Departs from the usual {@code ApplicationRunner} contract on purpose: an exception here is
     * swallowed instead of aborting the boot, because this is best-effort housekeeping on historical
     * rows and a service that refuses to start is far worse than one holding a few numbers in their
     * original format.
     *
     * @param args unused; the backfill covers every stored profile and takes no parameters
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            backfillPhoneNumbers();
        } catch (RuntimeException e) {
            logger.error("Phone number backfill did not run. Existing profiles keep whatever format "
                    + "they were stored in; nothing else is affected.", e);
        }
    }

    /**
     * Normalizes every stored profile phone number that is not already in E.164 form.
     *
     * <p>Scans and saves the whole {@code user_profiles} table inside one transaction, so either
     * every rewritten number is committed or none is; there is no partial backfill to reason about.
     * Rows whose number cannot be resolved unambiguously are left exactly as they are and reported
     * by user id in a warning, rather than guessed at — an incorrectly "fixed" number is a login
     * code delivered to a stranger.
     *
     * <p>Safe to run repeatedly: already-normalized and blank numbers are skipped, so a second pass
     * writes nothing.
     */
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
