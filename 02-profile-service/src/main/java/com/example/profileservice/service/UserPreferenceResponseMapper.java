package com.example.profileservice.service;

import org.springframework.stereotype.Component;

import com.example.profileservice.controller.PreferenceController.UserPreferenceResponse;
import com.example.profileservice.model.UserPreferenceEntity;
import com.example.profileservice.model.UserProfile;
import com.example.profileservice.repository.UserProfileRepository;

/**
 * Builds the preferences response, joining the preference row to the profile row that holds the
 * email address.
 *
 * <p>Preferences and profile are separate tables keyed by the same user id, so the response cannot
 * be assembled from either alone. Shared by {@code PreferenceController} (the authenticated
 * {@code /me} endpoint) and {@code InternalPreferenceController} (the service-to-service ones) so
 * the two can never drift into returning different shapes for the same data.
 */
@Component
public class UserPreferenceResponseMapper {

    private final UserProfileRepository userProfileRepository;

    public UserPreferenceResponseMapper(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    /**
     * Maps a preference row to its API response, filling in the user's email and send hour.
     *
     * <p>Reads the profile table, so this is a database call per invocation, not a pure conversion.
     *
     * @param entity never {@code null}; its {@code userId} need not have a profile row
     * @return a response whose {@code email} is {@code null} when the user has no profile row or
     *     predates the email column — notification-service reads that as "cannot email this user"
     *     rather than sending somewhere undeliverable — and whose {@code dailySummaryHour} is never
     *     {@code null}, falling back to {@link PreferenceService#DEFAULT_DAILY_SUMMARY_HOUR} for
     *     rows written before that column existed, because the consumer compares it against the
     *     current hour as an {@code int} and a {@code null} would fail mid-sweep
     */
    public UserPreferenceResponse toResponse(UserPreferenceEntity entity) {
        String email = userProfileRepository.findById(entity.getUserId())
                .map(UserProfile::getEmail)
                .orElse(null);

        Integer dailySummaryHour = entity.getDailySummaryHour() != null
                ? entity.getDailySummaryHour()
                : PreferenceService.DEFAULT_DAILY_SUMMARY_HOUR;

        return new UserPreferenceResponse(
                entity.getUserId(),
                entity.getAlertThresholdAmount(),
                entity.getDailySummaryEnabled(),
                entity.getTimezone(),
                dailySummaryHour,
                email);
    }
}
