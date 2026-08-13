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

@Service
public class PreferenceService {

    // Mirrors the column defaults in V3__Update_Preferences_Schema.sql, so a brand-new user's
    // untouched fields end up with the same values whether created lazily here (first PUT to
    // either endpoint) or seen for the first time by the Notification Service.
    private static final BigDecimal DEFAULT_ALERT_THRESHOLD = new BigDecimal("100.00");
    private static final boolean DEFAULT_DAILY_SUMMARY_ENABLED = false;
    private static final String DEFAULT_TIMEZONE = "UTC";
    // 8 is what notification.daily-summary.hour shipped as when the send hour was one global config
    // value, so a user who never picks an hour keeps being emailed at exactly the time they are now.
    // Same number as V6__Add_Daily_Summary_Hour.sql's column default, for the same reason as above.
    // Public because UserPreferenceResponseMapper answers with the same value for a row that predates
    // the column, so the two cannot disagree about what "never chose an hour" means.
    public static final int DEFAULT_DAILY_SUMMARY_HOUR = 8;

    private final PreferenceRepository preferenceRepository;

    public PreferenceService(PreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

    // the #userId inside key = is spring expression language reaching into the method's own
    // parameter by name, this is how it knows exactly which redis cache entry to evict
    @Transactional
    @CacheEvict(value = "user-preferences", key = "#userId")
    public void updateAlertThreshold(Long userId, BigDecimal alertThresholdAmount) {
        UserPreferenceEntity entity = findOrCreateDefault(userId);
        entity.setAlertThresholdAmount(alertThresholdAmount);
        preferenceRepository.save(entity);
    }

    @Transactional
    @CacheEvict(value = "user-preferences", key = "#userId")
    public void updateDailySummarySettings(Long userId, Boolean dailySummaryEnabled, String timezone,
                                           Integer dailySummaryHour) {
        // Strict Domain Validation: Ensure the timezone is a valid IANA identifier.
        try {
            ZoneId.of(timezone);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timezone identifier. Use IANA formats like 'America/New_York'.");
        }

        UserPreferenceEntity entity = findOrCreateDefault(userId);
        entity.setDailySummaryEnabled(dailySummaryEnabled);
        entity.setTimezone(timezone);
        // A null hour is "the caller did not mention the hour", so whatever the user already chose
        // stands - overwriting it with the default here would quietly move the send time of anyone
        // whose client only knows how to send the toggle and the timezone.
        if (dailySummaryHour != null) {
            entity.setDailySummaryHour(dailySummaryHour);
        }
        preferenceRepository.save(entity);
    }

    // orElseGet takes a supplier instead of a plain value like orElse does, so the new entity
    // only actually gets constructed when the optional is truly empty, not on every single call
    private UserPreferenceEntity findOrCreateDefault(Long userId) {
        return preferenceRepository.findByUserId(userId).orElseGet(() -> buildDefault(userId));
    }

    @Transactional(readOnly = true)
    public UserPreferenceEntity getPreferences(Long userId) {
        return preferenceRepository.findByUserId(userId).orElseGet(() -> buildDefault(userId));
    }

    // A null/blank timezone means "every opted-in user, whatever their zone". Now that each user
    // picks their own hour, the caller can no longer work out which timezones are currently at the
    // send hour and ask for just those - every zone is a potential match on every sweep, so the job
    // fetches the opt-ins once and compares each user's own hour locally instead of issuing one
    // request per zone. Passing a timezone still filters exactly as it always did, because
    // notification-service's manual trigger endpoint still asks for a single zone.
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
