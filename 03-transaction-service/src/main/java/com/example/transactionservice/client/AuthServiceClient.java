package com.example.transactionservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Resolves a user id to a human-readable name for the transfer recipient confirmation.
 *
 * <p>Exists only because no other service can answer the question: account-service maps an account
 * number to a user id but holds no names, and profile-service has no name field either, leaving the
 * username on auth-service's user row as the only human-readable identifier in the system.
 */
@FeignClient(name = "auth-service", url = "${application.client.auth-service.url:http://localhost:8081}")
public interface AuthServiceClient {

    /**
     * Name shown to a sender so they can confirm who they are about to pay.
     *
     * @param userId the account owner's numeric id, echoed back from the request
     * @param displayName the owner's username; may be {@code null} if auth-service holds no usable
     *     value, in which case the sender sees only a masked account number
     */
    record DisplayNameResponse(Long userId, String displayName) {}

    /**
     * Returns the display name of an account owner.
     *
     * <p>Discloses a name to a sender who already knows the recipient's account number, which is
     * deliberate: the pairing is what lets them catch a mistyped number before money moves. Nothing
     * else about the recipient crosses this boundary.
     *
     * @param userId owner id resolved from an account lookup, never {@code null}
     * @return the name record; treat both a {@code null} response and a {@code null}
     *     {@code displayName} as "unknown name" rather than as an error — a nameless recipient is
     *     still payable
     */
    @GetMapping("/api/v1/internal/users/{userId}/display-name")
    DisplayNameResponse getDisplayName(@PathVariable("userId") Long userId);
}
