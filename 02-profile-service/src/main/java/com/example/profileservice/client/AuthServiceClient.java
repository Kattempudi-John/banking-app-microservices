package com.example.profileservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

// auth-service owns the phone number, not this service. Its users.phone_number is the number 2FA
// codes are actually delivered to, and it is the one enforcing uniqueness at signup - so the identity
// form here has to write through to it rather than keeping a second, independent copy. Before this
// existed, two users could end up holding the same number, and "changing" a number on the Profile
// page left login codes still going to the old one.
//
// In production, 'url' is omitted in favor of a Service Registry like Netflix Eureka.
// learned feign is a declarative http client, this whole interface has no actual implementation
// body anywhere, spring generates the real http call at startup just from these annotations
@FeignClient(name = "auth-service", url = "${application.client.auth-service.url:http://localhost:8081}")
public interface AuthServiceClient {

    // phoneNumber may be null - a user who has never given one still resolves fine
    record PhoneNumberResponse(String phoneNumber) {}

    // Deliberately the raw string as the user typed it. auth-service does the normalizing, so it
    // never sees a number this service has already reshaped into a different format than the one the
    // uniqueness check runs against.
    record UpdatePhoneNumberRequest(String phoneNumber) {}

    // the @GetMapping/@PathVariable annotations here look identical to a controller, but on a
    // feign client they describe an outgoing request instead of an incoming one
    // Under /api/v1/internal/ so auth-service can leave it unauthenticated (there is no end-user
    // token on this call) without the k8s ingress publishing it to the internet.
    @GetMapping("/api/v1/internal/users/{userId}/phone-number")
    PhoneNumberResponse getPhoneNumber(@PathVariable("userId") Long userId);

    // Answers 409 if the number belongs to a DIFFERENT user, and 400 if it can't be resolved to a
    // real number at all. Re-submitting your own unchanged number is a success, not a conflict -
    // otherwise nobody could ever edit their address without also changing their phone number.
    // FeignErrorConfig turns both of those into a ResponseStatusException the caller can catch.
    @PutMapping("/api/v1/internal/users/{userId}/phone-number")
    PhoneNumberResponse updatePhoneNumber(@PathVariable("userId") Long userId,
                                          @RequestBody UpdatePhoneNumberRequest request);
}
