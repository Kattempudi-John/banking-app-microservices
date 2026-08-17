package com.example.profileservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Reads and writes a user's phone number in auth-service, which owns that field.
 *
 * <p>auth-service's {@code users.phone_number} is the number 2FA codes are delivered to and the
 * copy uniqueness is enforced against at signup, so this service writes through to it rather than
 * keeping an independent second copy. Without that write-through, two users could hold the same
 * number and "changing" a number on the Profile page left login codes going to the old one.
 *
 * <p>Both operations are mapped under {@code /api/v1/internal/} so auth-service can leave them
 * unauthenticated — there is no end-user token on a service-to-service call — without the k8s
 * ingress publishing them to the internet.
 *
 * <p>{@code url} is only set for local runs; a real deployment omits it in favour of a service
 * registry.
 */
@FeignClient(name = "auth-service", url = "${application.client.auth-service.url:http://localhost:8081}")
public interface AuthServiceClient {

    /**
     * Carries the E.164 number auth-service holds for a user.
     *
     * @param phoneNumber {@code null} for a user who has never supplied one, which is not an error
     */
    record PhoneNumberResponse(String phoneNumber) {}

    /**
     * Carries a phone number to auth-service exactly as the user typed it.
     *
     * @param phoneNumber raw, un-normalized user input; auth-service does the normalizing so it
     *     never sees a number this service has already reshaped away from the format its uniqueness
     *     check runs against
     */
    record UpdatePhoneNumberRequest(String phoneNumber) {}

    /**
     * Returns the phone number auth-service currently holds for a user.
     *
     * @param userId never {@code null}; an id with no user in auth-service yields an empty number
     *     rather than an error
     * @return the owning service's copy, never the local mirror; the wrapped number may be
     *     {@code null}
     */
    @GetMapping("/api/v1/internal/users/{userId}/phone-number")
    PhoneNumberResponse getPhoneNumber(@PathVariable("userId") Long userId);

    /**
     * Registers a phone number against a user in auth-service and returns the stored E.164 form.
     *
     * <p>Re-submitting a user's own unchanged number succeeds rather than conflicting, so a user can
     * edit their address without also having to change their phone number.
     *
     * @param userId never {@code null}; the user the number is claimed for
     * @param request raw user input, not pre-normalized
     * @return the normalized number auth-service accepted and stored; treat this as the value to
     *     persist locally rather than normalizing a second time
     * @throws org.springframework.web.server.ResponseStatusException {@code 409} when the number is
     *     already registered to a different user, or {@code 400} when it cannot be resolved to a
     *     real number, both translated by {@code FeignErrorConfig}
     */
    @PutMapping("/api/v1/internal/users/{userId}/phone-number")
    PhoneNumberResponse updatePhoneNumber(@PathVariable("userId") Long userId,
                                          @RequestBody UpdatePhoneNumberRequest request);
}
