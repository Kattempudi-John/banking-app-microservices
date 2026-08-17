package com.example.accountservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * Reads a user's KYC verification status from profile-service, the owner of that state.
 *
 * <p>Duplicated rather than shared with transaction-service, which asks the same question of the
 * same endpoint: the services are independently deployable and a shared client jar would tie their
 * release cycles together for one method.
 *
 * <p>The base URL comes from {@code application.client.profile-service.url} and falls back to
 * {@code http://localhost:8082} when unset.
 */
@FeignClient(name = "profile-service", url = "${application.client.profile-service.url:http://localhost:8082}")
public interface ProfileServiceClient {

    /**
     * Fetches the KYC decision recorded for a user.
     *
     * <p>Sits under {@code /api/v1/internal/} so profile-service can serve it unauthenticated —
     * there is no end-user token on this hop — while the k8s ingress never publishes the path.
     *
     * <p>The response is expected to carry a {@code status} entry holding the decision name
     * ({@code APPROVED}, {@code PENDING_VERIFICATION}, and so on). Callers must treat a
     * {@code null} body or a body without that key as "unknown", not as approval; an unreachable
     * profile-service surfaces as a Feign runtime exception rather than as an empty result.
     *
     * @param userId identifies the person being checked, never {@code null}; an id with no profile
     *     yields a body carrying no {@code status} rather than a 404
     * @return the status map as profile-service returned it, possibly {@code null} or missing the
     *     {@code status} key
     */
    @GetMapping("/api/v1/internal/profiles/{userId}/kyc-status")
    Map<String, String> getKycStatus(@PathVariable("userId") Long userId);

}
