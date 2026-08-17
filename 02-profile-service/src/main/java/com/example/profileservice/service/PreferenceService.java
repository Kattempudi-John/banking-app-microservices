package com.example.profileservice.service;

import com.example.profileservice.model.UserPreferenceEntity;
import com.example.profileservice.repository.PreferenceRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;

/**
 * Reads and updates a user's notification preferences, creating a defaulted row on first use.
 *
 * <p>No user is required to have a preference row: every entry point here falls back to a default
 * entity, so a caller never has to check for existence first. The defaults mirror the column
 * defaults in the Flyway migrations, so a brand-new user's untouched fields hold the same values
 * whether the row was created lazily here or has yet to be created at all.
 *
 * <p>Each write evicts the {@code user-preferences} cache entry for that user, since
 * notification-service reads these values on every alert and would otherwise keep acting on the
 * superseded threshold.
 */
@Service
public class PreferenceService {

    private static final BigDecimal DEFAULT_ALERT_THRESHOLD = new BigDecimal("100.00");
    private static final boolean DEFAULT_DAILY_SUMMARY_ENABLED = false;
    private static final String DEFAULT_TIMEZONE = "UTC";

    /**
     * Hour of day a daily summary is sent to a user who has never chosen one.
     *
     * <p>{@code 8} is what {@code notification.daily-summary.hour} shipped as when the send hour was
     * a single global config value, so users who never pick an hour keep being emailed at exactly
     * the time they already were. Matches the column default in
     * {@code V6__Add_Daily_Summary_Hour.sql}.
     *
     * <p>Public because {@link UserPreferenceResponseMapper} substitutes the same value for rows
     * predating that column; the two must not disagree about what "never chose an hour" means.
     */
    public static final int DEFAULT_DAILY_SUMMARY_HOUR = 8;

    private final PreferenceRepository preferenceRepository;

    public PreferenceService(PreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

    /**
     * Sets the balance-change amount above which a user is alerted.
     *
     * <p>Creates a defaulted preference row when the user has none, so this doubles as first-time
     * opt-in. The user's cached preferences are evicted as part of the same call.
     *
     * @param userId never {@code null}; need not already have a preference row
     * @param alertThresholdAmount stored as given, including {@code null}; range is enforced by the
     *     request DTO, not here
     */
    @Transactional
    @CacheEvict(value = "user-preferences", key = "#userId")
    public void updateAlertThreshold(Long userId, BigDecimal alertThresholdAmount) {
        UserPreferenceEntity entity = findOrCreateDefault(userId);
        entity.setAlertThresholdAmount(alertThresholdAmount);
        preferenceRepository.save(entity);
    }

    /**
     * Sets the daily-summary opt-in, timezone, and send hour, creating the row if absent.
     *
     * <p>The timezone is validated before anything is written, so a rejected zone leaves the stored
     * preferences untouched rather than half-applied.
     *
     * @param userId never {@code null}; need not already have a preference row
     * @param dailySummaryEnabled stored as given
     * @param timezone must be a resolvable IANA identifier such as {@code America/New_York}
     * @param dailySummaryHour optional: {@code null} means "the caller did not mention the hour" and
     *     leaves whatever the user already chose in place, so a client that only knows how to send
     *     the toggle and the zone cannot silently move someone's send time
     * @throws ResponseStatusException {@code 400} when {@code timezone} is not a valid IANA zone
     */
    @Transactional
    @CacheEvict(value = "user-preferences", key = "#userId")
    public void updateDailySummarySettings(Long userId, Boolean dailySummaryEnabled, String timezone,
                                           Integer dailySummaryHour) {
        try {
            ZoneId.of(timezone);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timezone identifier. Use IANA formats like 'America/New_York'.");
        }

        UserPreferenceEntity entity = findOrCreateDefault(userId);
        entity.setDailySummaryEnabled(dailySummaryEnabled);
        entity.setTimezone(timezone);
        if (dailySummaryHour != null) {
            entity.setDailySummaryHour(dailySummaryHour);
        }
        preferenceRepository.save(entity);
    }

    private UserPreferenceEntity findOrCreateDefault(Long userId) {
        return preferenceRepository.findByUserId(userId).orElseGet(() -> buildDefault(userId));
    }

    /**
     * Returns a user's preferences, or a defaulted set when they have never saved any.
     *
     * @param userId never {@code null}; an unknown id is not an error
     * @return never {@code null}; an unsaved, in-memory default entity for a user with no row, so
     *     callers must not assume the result corresponds to a persisted record
     */
    @Transactional(readOnly = true)
    public UserPreferenceEntity getPreferences(Long userId) {
        return preferenceRepository.findByUserId(userId).orElseGet(() -> buildDefault(userId));
    }

    /**
     * Lists the users opted in to the daily summary, optionally narrowed to one timezone.
     *
     * <p>Now that each user picks their own send hour, the caller can no longer work out which zones
     * are currently at the send hour and ask for only those — every zone is a potential match on
     * every sweep. The hourly job therefore fetches the whole opt-in list once and compares each
     * user's own hour locally, instead of issuing one request per zone.
     *
     * @param timezone {@code null} or blank returns every opted-in user regardless of zone; an IANA
     *     identifier filters to exactly that zone, which is what the manual trigger endpoint uses
     * @return possibly empty, never {@code null}; opted-out users are excluded
     */
    @Transactional(readOnly = true)
    public List<UserPreferenceEntity> getUsersForDailySummary(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return preferenceRepository.findByDailySummaryEnabledTrue();
        }
        return preferenceRepository.findByDailySummaryEnabledTrueAndTimezone(timezone);
    }

    private UserPreferenceEntity buildDefault(Long userId) {
        UserPreferenceEntity newEntity = new UserPreferenceEntity();
        newEntity.setUserId(userId);
        newEntity.setAlertThresholdAmount(DEFAULT_ALERT_THRESHOLD);
        newEntity.setDailySummaryEnabled(DEFAULT_DAILY_SUMMARY_ENABLED);
        newEntity.setTimezone(DEFAULT_TIMEZONE);
        newEntity.setDailySummaryHour(DEFAULT_DAILY_SUMMARY_HOUR);
        return newEntity;
    }
}
