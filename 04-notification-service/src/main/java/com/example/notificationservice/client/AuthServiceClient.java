package com.example.notificationservice.client;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Resolves a user ID into a human-readable name for use in alert copy.
 *
 * <p>A declarative Feign client — the implementation is generated at runtime, unlike
 * {@link EmailProviderClient} and {@link SmsProviderClient} in the same package, which are plain
 * interfaces with hand-written implementations. The base URL comes from {@code auth-service.url},
 * defaulting to {@code http://localhost:8081} for local runs.
 *
 * <p>It exists purely for the name on a transaction alert. "Money left for account ....5570" makes
 * the reader go and look the number up; "money left for Mark" is recognised, or not, at a glance —
 * and an unrecognised name is how a user spots a transfer they did not make. auth-service is the
 * only source: account-service maps an account to a user but has no concept of names, and
 * profile-service has no name field either, so the username on the auth-service user row is the
 * system's only human-readable identifier. transaction-service calls the same endpoint from its own
 * side for the same reason.
 *
 * <p>The endpoint sits under {@code /api/v1/internal/} so the Kubernetes ingress does not expose it
 * publicly; it is unauthenticated because a service-to-service call carries no end-user token.
 */
@FeignClient(name = "auth-service", url = "${auth-service.url:http://localhost:8081}")
public interface AuthServiceClient {

    record DisplayNameResponse(Long userId, String displayName) {}

    /**
     * Returns the display name for a user, served from the {@code user-display-names} cache when
     * possible.
     *
     * <p>Caching is worth it here because a display name is near-static and the same counterparty
     * recurs across a run of alerts — a user paying the same person repeatedly — so a burst of
     * alerts collapses to one HTTP call instead of one per alert. Note the cache has no TTL and
     * nothing evicts it: a name changed in auth-service is served stale by this service until it
     * restarts.
     *
     * @param userId an existing user ID; an unknown one is a 404 from auth-service, surfaced as an
     *     exception rather than a {@code null} result
     * @return the user's display name; a {@code null} result is not cached, so the lookup is
     *     retried on the next alert rather than pinned
     */
    @GetMapping("/api/v1/internal/users/{userId}/display-name")
    @Cacheable(value = "user-display-names", key = "#userId", unless = "#result == null")
    DisplayNameResponse getDisplayName(@PathVariable("userId") Long userId);
}
