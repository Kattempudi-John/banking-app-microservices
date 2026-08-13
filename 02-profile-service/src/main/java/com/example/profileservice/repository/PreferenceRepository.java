package com.example.profileservice.repository;

import com.example.profileservice.model.UserPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PreferenceRepository extends JpaRepository<UserPreferenceEntity, Long> {
    Optional<UserPreferenceEntity> findByUserId(Long userId);

    List<UserPreferenceEntity> findByDailySummaryEnabledTrueAndTimezone(String timezone);

    // The unfiltered sweep behind an omitted ?timezone: every opted-in user in one call, for the
    // daily-summary job that now matches each user's own hour itself rather than asking this service
    // for one timezone at a time (~600 round trips an hour once the hour stopped being global).
    List<UserPreferenceEntity> findByDailySummaryEnabledTrue();
}