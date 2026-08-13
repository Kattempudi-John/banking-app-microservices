package com.example.accountservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import feign.RequestInterceptor;

// The outbound half of the shared-secret change. ProfileServiceClient calls profile-service's
// /api/v1/internal/profiles/{userId}/kyc-status, and profile-service is putting the very same
// filter in front of its own internal prefix - so without this interceptor every KYC check would
// come back 401, KycEnforcementAspect fails closed by design, and every deposit and account
// opening in this service would start refusing.
//
// A plain @Bean here rather than a `configuration = ...` class attached to @FeignClient: that form
// builds an isolated child context per client, so the bean would have to be duplicated for each new
// client added later, and forgetting it is a silent 401 at runtime rather than a compile error.
// Declaring it globally means any future internal client this service grows is authenticated by
// default - the safe direction to fail.
@Configuration
public class FeignInternalTokenConfig {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    // Same property and same dev default as the inbound InternalTokenFilter reads, deliberately:
    // in a single-secret deployment this service's own credential is the one it presents to others.
    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    @Bean
    public RequestInterceptor internalTokenRequestInterceptor() {
        return template -> template.header(INTERNAL_TOKEN_HEADER, internalToken);
    }
}
