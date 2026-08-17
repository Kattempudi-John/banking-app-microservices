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

/**
 * Serves notification preferences to other services.
 *
 * <p>The service-to-service half of {@link PreferenceController}: notification-service reads a
 * user's alert threshold and delivery address before every alert, and sweeps the daily-summary
 * opt-ins hourly. Neither call carries an end-user token, so neither can sit behind the JWT rule;
 * both are authorized instead by the shared token {@code InternalTokenFilter} requires.
 *
 * <p>These paths are declared in full here rather than added to {@link PreferenceController} because
 * that class is mapped under {@code /api/v1/profile/alerts}, which the k8s ingress routes. An
 * unauthenticated endpoint under a routed prefix is an endpoint published to the internet — and
 * these responses carry email addresses.
 */
@RestController
public class InternalPreferenceController {

    private final PreferenceService preferenceService;
    private final UserPreferenceResponseMapper responseMapper;

    public InternalPreferenceController(PreferenceService preferenceService,
                                         UserPreferenceResponseMapper responseMapper) {
        this.preferenceService = preferenceService;
        this.responseMapper = responseMapper;
    }

    /**
     * Returns one user's notification preferences to a calling service.
     *
     * @param userId never {@code null}; taken from the path, so this can be pointed at any user and
     *     must stay unreachable from the internet
     * @return {@code 200} always; an unknown user yields the default preferences with a {@code null}
     *     email rather than a {@code 404}, so callers need no separate "user has no preferences" path
     */
    @GetMapping("/api/v1/internal/profiles/{userId}/preferences")
    public ResponseEntity<UserPreferenceResponse> getPreferences(@PathVariable Long userId) {
        return ResponseEntity.ok(responseMapper.toResponse(preferenceService.getPreferences(userId)));
    }

    /**
     * Lists the users opted in to the daily summary, optionally narrowed to one timezone.
     *
     * @param timezone optional: omitted, this answers with every opted-in user regardless of zone.
     *     The hourly job needs that because the send hour is now per-user, so it can no longer narrow
     *     the sweep to "the zones where it is currently 08:00" — it takes the whole opt-in list once
     *     and matches each user's own hour itself. Supplying an IANA identifier still filters to that
     *     single zone, which is what notification-service's manual trigger endpoint does
     * @return {@code 200} with a possibly empty list; each entry carries the user's own send hour,
     *     never {@code null}, which the caller compares against the current hour
     */
    @GetMapping("/api/v1/internal/profiles/daily-summary-users")
    public ResponseEntity<List<UserPreferenceResponse>> getUsersForDailySummary(
            @RequestParam(required = false) String timezone) {
        List<UserPreferenceResponse> users = preferenceService.getUsersForDailySummary(timezone).stream()
                .map(responseMapper::toResponse)
                .toList();
        return ResponseEntity.ok(users);
    }
}
