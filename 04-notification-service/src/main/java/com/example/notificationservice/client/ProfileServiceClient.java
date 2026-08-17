package com.example.notificationservice.client;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;

/**
 * Reads notification preferences and daily-summary opt-ins from profile-service.
 *
 * <p>A declarative Feign client — the implementation is generated at runtime, unlike
 * {@link EmailProviderClient} and {@link SmsProviderClient} in the same package, which are plain
 * interfaces with hand-written implementations. The base URL comes from
 * {@code profile-service.url}, defaulting to {@code http://localhost:8082} for local runs.
 *
 * <p>Every endpoint sits under {@code /api/v1/internal/} rather than the public
 * {@code /api/v1/profile/} prefix the Kubernetes ingress routes. They are unauthenticated by
 * necessity — a service-to-service call carries no end-user token — and the preference response now
 * carries an email address, so the internal prefix is what keeps them off the public internet.
 */
@FeignClient(name = "profile-service", url = "${profile-service.url:http://localhost:8082}")
public interface ProfileServiceClient {

    /**
     * A user's notification preferences as profile-service holds them.
     *
     * <p>Two fields are nullable in ways that matter to callers. {@code email} is {@code null} for
     * users who registered before the field existed, and every caller must check rather than send to
     * an address that cannot receive anything. {@code dailySummaryHour} is the local hour 0-23 the
     * user chose and is boxed rather than an {@code int} on purpose: profile-service defaults it to
     * 8, but a row written before the column existed still answers {@code null}, and an {@code int}
     * would silently turn that into midnight — a summary at 00:00 for someone who never asked for
     * one. Callers treat {@code null} as unknown and skip the user.
     */
    record UserPreferenceResponse(
            Long userId,
            BigDecimal alertThresholdAmount,
            Boolean dailySummaryEnabled,
            Integer dailySummaryHour,
            String timezone,
            String email
    ) {}

    /**
     * Returns one user's notification preferences, served from the {@code user-preferences} cache
     * when possible.
     *
     * <p>The cache wraps the HTTP call itself, so a hit never leaves the process. Preferences change
     * rarely and are read on every alert, which is what makes that worth doing — but the cache has
     * no TTL and nothing evicts it, so a preference a user changes in the UI is not honoured by this
     * service until it restarts.
     *
     * @param userId an existing user ID; an unknown one is a 404 from profile-service, surfaced as
     *     an exception rather than a {@code null} result
     * @return the user's preferences, with {@code email} and {@code dailySummaryHour} both possibly
     *     {@code null}; a {@code null} result is not cached
     */
    @GetMapping("/api/v1/internal/profiles/{userId}/preferences")
    @Cacheable(value = "user-preferences", key = "#userId", unless = "#result == null")
    UserPreferenceResponse getUserPreferences(@PathVariable("userId") Long userId);

    /**
     * Returns every user opted in to the daily summary, in any timezone.
     *
     * <p>This is what the hourly sweep calls. Omitting the {@code timezone} query parameter means
     * "all zones" to profile-service; since the summary hour became per-user, any zone on earth may
     * be due in the current hour, so asking zone by zone would be roughly 600 HTTP calls to answer
     * what one call answers.
     *
     * <p>It is a separate declaration from {@link #getUsersForDailySummary(String)} rather than the
     * same method passed {@code null}. Feign does drop a {@code null} query parameter, so that would
     * work, but {@code getUsersForDailySummary(null)} reads like a bug at the call site.
     *
     * <p>Not cached, unlike {@link #getUserPreferences}: the sweep needs current opt-ins.
     *
     * @return every opted-in user, empty when nobody is opted in, never {@code null}
     */
    @GetMapping("/api/v1/internal/profiles/daily-summary-users")
    List<UserPreferenceResponse> getAllUsersForDailySummary();

    /**
     * Returns the users opted in to the daily summary within one timezone.
     *
     * <p>The single-zone form, used by the manual trigger in {@code InternalNotificationController}
     * so an operator asking for one region does not pull down and filter the whole opted-in
     * population.
     *
     * @param timezone an IANA zone ID such as {@code Europe/London}; an unrecognised value matches
     *     nothing rather than failing
     * @return the matching users, empty when the zone has no opted-in users, never {@code null}
     */
    @GetMapping("/api/v1/internal/profiles/daily-summary-users")
    List<UserPreferenceResponse> getUsersForDailySummary(@RequestParam("timezone") String timezone);
}