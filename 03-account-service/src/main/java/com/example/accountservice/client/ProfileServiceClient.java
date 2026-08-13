package com.example.accountservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

// Same client transaction-service already uses for the same question, pointed at the same endpoint.
// Deliberately a copy rather than a shared module: these services are independently deployable, and
// a shared client jar would couple their release cycles together for one method.
@FeignClient(name = "profile-service", url = "${application.client.profile-service.url:http://localhost:8082}")
public interface ProfileServiceClient {

    // Under /api/v1/internal/ so profile-service can leave it unauthenticated - there is no end-user
    // token on this call - without the k8s ingress ever publishing it.
    @GetMapping("/api/v1/internal/profiles/{userId}/kyc-status")
    Map<String, String> getKycStatus(@PathVariable("userId") Long userId);

}
