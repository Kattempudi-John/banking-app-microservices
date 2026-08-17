package com.example.profileservice.controller;

import com.example.profileservice.dto.UpdateAlertThresholdRequestDto;
import com.example.profileservice.dto.UpdateDailySummaryRequestDto;
import com.example.profileservice.model.UserPreferenceEntity;
import com.example.profileservice.service.PreferenceService;
import com.example.profileservice.service.UserPreferenceResponseMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Exposes the authenticated user's notification preferences to the Alert Preferences page.
 *
 * <p>Every endpoint here requires {@code SCOPE_FULL_AUTH}, declared once at class level so no future
 * method can be added without it, and none of them accepts a user id — it is always read from the
 * JWT. That matters more than usual because these responses carry an email address, so an id
 * parameter would let one user read another's.
 *
 * <p>The service-to-service equivalents live on {@link InternalPreferenceController}, which is
 * unauthenticated and therefore kept off this ingress-routed prefix.
 */
@RestController
@RequestMapping("/api/v1/profile/alerts")
@PreAuthorize("hasAuthority('SCOPE_FULL_AUTH')")
public class PreferenceController {

    private final PreferenceService preferenceService;
    private final UserPreferenceResponseMapper responseMapper;

    public PreferenceController(PreferenceService preferenceService, UserPreferenceResponseMapper responseMapper) {
        this.preferenceService = preferenceService;
        this.responseMapper = responseMapper;
    }

    /**
     * Sets the balance-change amount above which the caller is alerted.
     *
     * @param request validated before the body runs, so an out-of-range amount answers {@code 400}
     *     with the DTO constraint's own message; applies to the caller's own preferences only
     * @return {@code 200} with a plain-text confirmation, also for a user who had no stored
     *     preferences before this call
     */
    @PutMapping("/threshold")
    public ResponseEntity<String> updateAlertThreshold(
            @RequestBody @Valid UpdateAlertThresholdRequestDto request) {

        Long userId = extractUserIdFromAuth();
        preferenceService.updateAlertThreshold(userId, request.alertThresholdAmount());

        return ResponseEntity.ok("Alert threshold preferences successfully updated.");
    }

    /**
     * Sets the caller's daily-summary opt-in, timezone, and send hour.
     *
     * @param request validated before the body runs; the timezone must be an IANA identifier, and an
     *     omitted {@code dailySummaryHour} leaves the user's existing choice untouched rather than
     *     resetting it
     * @return {@code 200} with a plain-text confirmation
     * @throws org.springframework.web.server.ResponseStatusException {@code 400} when the timezone is
     *     not a recognized IANA zone, in which case nothing is stored
     */
    @PutMapping("/daily-summary")
    public ResponseEntity<String> updateDailySummarySettings(
            @RequestBody @Valid UpdateDailySummaryRequestDto request) {

        Long userId = extractUserIdFromAuth();
        preferenceService.updateDailySummarySettings(userId, request.dailySummaryEnabled(), request.timezone(),
                request.dailySummaryHour());

        return ResponseEntity.ok("Daily summary preferences successfully updated.");
    }

    /**
     * Carries a user's notification preferences, shared by this controller and the internal one.
     *
     * @param userId never {@code null}; the user these preferences belong to
     * @param alertThresholdAmount the amount above which an alert is sent
     * @param dailySummaryEnabled whether the user has opted in to the daily summary
     * @param timezone IANA identifier the send hour is interpreted in
     * @param dailySummaryHour never {@code null}, defaulted for rows predating the column; rides
     *     along here rather than on its own endpoint because the page pre-fills the hour picker from
     *     this one response and notification-service's sweep decides who to send to from it without a
     *     second lookup per user
     * @param email {@code null} when the user has no address on file, which callers read as "cannot
     *     email this user"; included here because every caller that needs to email a user already
     *     fetches their preferences first, saving a round trip per send
     */
    public record UserPreferenceResponse(
            Long userId,
            BigDecimal alertThresholdAmount,
            Boolean dailySummaryEnabled,
            String timezone,
            Integer dailySummaryHour,
            String email
    ) {}

    /**
     * Returns the caller's own notification preferences.
     *
     * @return {@code 200} always; a user who has never saved any preferences gets the defaults rather
     *     than a {@code 404}, so the page can render without special-casing first-time users
     */
    @GetMapping("/me")
    public ResponseEntity<UserPreferenceResponse> getMyPreferences() {
        Long userId = extractUserIdFromAuth();
        return ResponseEntity.ok(toResponse(preferenceService.getPreferences(userId)));
    }

    private UserPreferenceResponse toResponse(UserPreferenceEntity entity) {
        return responseMapper.toResponse(entity);
    }

    private Long extractUserIdFromAuth() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new SecurityException("User is not authenticated");
        }
        if (!authentication.isAuthenticated()) {
            throw new SecurityException("User is not authenticated");
        }
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getClaim("userId");
    }
}
