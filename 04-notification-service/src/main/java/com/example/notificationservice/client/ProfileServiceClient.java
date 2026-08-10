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
    record UserPreferenceResponse(
            Long userId,
            BigDecimal alertThresholdAmount,
            Boolean dailySummaryEnabled,
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

    @GetMapping("/api/v1/internal/profiles/daily-summary-users")
    List<UserPreferenceResponse> getUsersForDailySummary(@RequestParam("timezone") String timezone);
}