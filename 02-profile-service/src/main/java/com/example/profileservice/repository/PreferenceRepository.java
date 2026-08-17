package com.example.profileservice.repository;

import com.example.profileservice.model.UserPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence access for {@link UserPreferenceEntity}.
 */
@Repository
public interface PreferenceRepository extends JpaRepository<UserPreferenceEntity, Long> {

    /**
     * Finds a customer's preferences.
     *
     * <p>{@code userId} is a plain column, not the primary key, so this is the lookup to use rather
     * than {@code findById}.
     *
     * @param userId an auth-service user id
     * @return empty for a customer who has never saved a preference, which callers must treat as
     *     "defaults apply" rather than as an error
     */
    Optional<UserPreferenceEntity> findByUserId(Long userId);

    /**
     * Lists everyone opted into the daily summary within one timezone.
     *
     * @param timezone an IANA zone id, matched exactly — an equivalent zone under a different name
     *     will not match
     * @return empty when nobody in that zone has opted in
     */
    List<UserPreferenceEntity> findByDailySummaryEnabledTrueAndTimezone(String timezone);

    /**
     * Lists everyone opted into the daily summary, in any timezone.
     *
     * <p>The unfiltered sweep served when {@code ?timezone} is omitted. It exists because the
     * daily-summary job now matches each user's own send hour itself instead of asking this service
     * one timezone at a time, which cost roughly 600 round trips an hour once the hour stopped being
     * global. The whole opted-in population comes back in one unpaged call, so this grows linearly
     * with opt-ins.
     *
     * @return empty when nobody has opted in
     */
    List<UserPreferenceEntity> findByDailySummaryEnabledTrue();
}