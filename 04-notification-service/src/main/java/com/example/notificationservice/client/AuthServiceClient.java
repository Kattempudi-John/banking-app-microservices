package com.example.notificationservice.client;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// Third Feign client here, and the only reason it exists is the name on a transaction alert: an
// alert that says money left for account ........5570 makes the reader look the number up, while one
// that says it left for Mark can be recognised (or not) at a glance. account-service can turn an
// account id into a user id but has no concept of names, and profile-service has no name field
// either - the username on auth-service's user row is the only human-readable identifier in the
// system. transaction-service reaches the same endpoint for the same reason (see its own
// AuthServiceClient), this is that lookup from the alerting side.
@FeignClient(name = "auth-service", url = "${auth-service.url:http://localhost:8081}")
public interface AuthServiceClient {

    record DisplayNameResponse(Long userId, String displayName) {}

    // Cached like ProfileServiceClient's preferences lookup: a display name is close to static, and
    // the same counterparty tends to show up across a run of alerts (a user paying the same person
    // repeatedly), so the cache turns each of those into one HTTP call rather than one per alert.
    @GetMapping("/api/v1/internal/users/{userId}/display-name")
    @Cacheable(value = "user-display-names", key = "#userId", unless = "#result == null")
    DisplayNameResponse getDisplayName(@PathVariable("userId") Long userId);
}
