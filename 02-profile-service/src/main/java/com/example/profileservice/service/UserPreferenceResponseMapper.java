package com.example.profileservice.service;

import org.springframework.stereotype.Component;

import com.example.profileservice.controller.PreferenceController.UserPreferenceResponse;
import com.example.profileservice.model.UserPreferenceEntity;
import com.example.profileservice.model.UserProfile;
import com.example.profileservice.repository.UserProfileRepository;

// Preferences and profile are separate tables keyed by the same user id, so building the response
// means reading from both. Shared by PreferenceController (the authenticated /me endpoint) and
// InternalPreferenceController (the service-to-service ones) so the two can never drift into
// returning different shapes for the same data.
@Component
public class UserPreferenceResponseMapper {

    private final UserProfileRepository userProfileRepository;

    public UserPreferenceResponseMapper(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    public UserPreferenceResponse toResponse(UserPreferenceEntity entity) {
        // A user with no profile row, or one registered before the email field existed, simply
        // reports a null address - notification-service treats that as "can't email this user"
        // rather than sending to somewhere undeliverable.
        String email = userProfileRepository.findById(entity.getUserId())
                .map(UserProfile::getEmail)
                .orElse(null);

        return new UserPreferenceResponse(
                entity.getUserId(),
                entity.getAlertThresholdAmount(),
                entity.getDailySummaryEnabled(),
                entity.getTimezone(),
                email);
    }
}
