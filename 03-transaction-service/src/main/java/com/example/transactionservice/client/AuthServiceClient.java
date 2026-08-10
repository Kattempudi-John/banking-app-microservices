package com.example.transactionservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// Only exists to answer "what is this account owner called?" for the transfer recipient confirmation.
// account-service can resolve an account number to a user id but has no concept of names, and
// profile-service has no name field either - the username on auth-service's user row is the only
// human-readable identifier in the system.
@FeignClient(name = "auth-service", url = "${application.client.auth-service.url:http://localhost:8081}")
public interface AuthServiceClient {

    record DisplayNameResponse(Long userId, String displayName) {}

    @GetMapping("/api/v1/internal/users/{userId}/display-name")
    DisplayNameResponse getDisplayName(@PathVariable("userId") Long userId);
}
