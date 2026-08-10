package com.example.profileservice.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.profileservice.controller.PreferenceController.UserPreferenceResponse;
import com.example.profileservice.service.PreferenceService;
import com.example.profileservice.service.UserPreferenceResponseMapper;

// The service-to-service half of PreferenceController. notification-service reads a user's alert
// threshold (and the email address to deliver to) before every alert, and sweeps the daily-summary
// opt-ins hourly - neither call carries an end-user token, so both have to be unauthenticated.
//
// They live here under /api/v1/internal/ rather than on PreferenceController because that class is
// mapped at /api/v1/profile/alerts, which the k8s ingress now routes. An unauthenticated endpoint
// under a routed prefix is an endpoint published to the internet - and this response carries email
// addresses, so that mattered more than usual.
@RestController
public class InternalPreferenceController {

    private final PreferenceService preferenceService;
    private final UserPreferenceResponseMapper responseMapper;

    public InternalPreferenceController(PreferenceService preferenceService,
                                         UserPreferenceResponseMapper responseMapper) {
        this.preferenceService = preferenceService;
        this.responseMapper = responseMapper;
    }

    @GetMapping("/api/v1/internal/profiles/{userId}/preferences")
    public ResponseEntity<UserPreferenceResponse> getPreferences(@PathVariable Long userId) {
        return ResponseEntity.ok(responseMapper.toResponse(preferenceService.getPreferences(userId)));
    }

    @GetMapping("/api/v1/internal/profiles/daily-summary-users")
    public ResponseEntity<List<UserPreferenceResponse>> getUsersForDailySummary(@RequestParam String timezone) {
        List<UserPreferenceResponse> users = preferenceService.getUsersForDailySummary(timezone).stream()
                .map(responseMapper::toResponse)
                .toList();
        return ResponseEntity.ok(users);
    }
}
