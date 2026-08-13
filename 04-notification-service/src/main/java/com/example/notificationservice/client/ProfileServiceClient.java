package com.example.notificationservice.client;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;

@FeignClient(name = "profile-service", url = "${profile-service.url:http://localhost:8082}")
public interface ProfileServiceClient {

    // email is null for users who registered before the field existed - every caller checks for that
    // rather than sending to an address that can't receive anything.
    //
    // dailySummaryHour is the whole local hour (0-23) the user picked for their summary, and it is
    // boxed rather than an int on purpose: profile-service defaults it to 8, but a row written before
    // the column existed still answers with null, and an int would silently turn that into midnight -
    // a summary at 00:00 for someone who never asked for one. Callers treat null as "unknown, skip".
    record UserPreferenceResponse(
            Long userId,
            BigDecimal alertThresholdAmount,
            Boolean dailySummaryEnabled,
            Integer dailySummaryHour,
            String timezone,
            String email
    ) {}

    // Both moved under /api/v1/internal/ - they're unauthenticated by necessity (no end-user token on
    // a service-to-service call) and this response now carries an email address, so they can't sit
    // under /api/v1/profile/alerts now that the k8s ingress routes that prefix.
    // learned @cacheable can go directly on a feign client method, not just on a normal service
    // method, spring wraps the whole call including the actual http request in a cache check first
    @GetMapping("/api/v1/internal/profiles/{userId}/preferences")
    @Cacheable(value = "user-preferences", key = "#userId", unless = "#result == null")
    UserPreferenceResponse getUserPreferences(@PathVariable("userId") Long userId);

    // Every opted-in user, whatever their timezone - profile-service treats the timezone param as
    // optional and omitting it means "all zones". This is what the hourly sweep calls: now that the
    // summary hour is per-user, every zone on earth is potentially due this hour, so asking zone by
    // zone would be ~600 HTTP calls to answer a question one call answers.
    //
    // Declared as its own method rather than reusing the one below with a null argument: Feign does
    // drop a null query param, but "getUsersForDailySummary(null)" at a call site reads like a bug,
    // and a second no-arg declaration on the same path costs nothing.
    @GetMapping("/api/v1/internal/profiles/daily-summary-users")
    List<UserPreferenceResponse> getAllUsersForDailySummary();

    // The single-zone form, still used by the manual trigger in InternalNotificationController - an
    // operator asking for one region shouldn't pull down and filter the whole opted-in population.
    @GetMapping("/api/v1/internal/profiles/daily-summary-users")
    List<UserPreferenceResponse> getUsersForDailySummary(@RequestParam("timezone") String timezone);
}