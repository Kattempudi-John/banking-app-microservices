package com.example.transactionservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * Reads a user's identity-verification decision from profile-service.
 *
 * <p>Declarative client with no implementation in this repository; the HTTP call is generated from
 * the annotations at startup. The {@code url} attribute is a local-development fallback only —
 * where a service registry is in use the property is set and the literal default is never reached.
 */
@FeignClient(name = "profile-service", url = "${application.client.profile-service.url:http://localhost:8082}")
public interface ProfileServiceClient {

    /**
     * Returns the KYC decision currently recorded against a user.
     *
     * <p>Routed under {@code /api/v1/internal/} so profile-service can serve it without an end-user
     * token — this hop carries none — while the ingress keeps that prefix off the public internet.
     *
     * <p>Every money movement in this service makes this call synchronously before it proceeds, so
     * profile-service latency is on the critical path of a transfer.
     *
     * @param userId numeric id taken from the JWT's {@code userId} claim, never {@code null}
     * @return single-entry map keyed {@code "status"}, typically {@code APPROVED},
     *     {@code PENDING_VERIFICATION}, or {@code REJECTED}; both a {@code null} body and a body
     *     without that key are possible answers, and callers must treat either as "not approved"
     *     rather than as approval
     */
    @GetMapping("/api/v1/internal/profiles/{userId}/kyc-status")
    Map<String, String> getKycStatus(@PathVariable("userId") Long userId);

}
