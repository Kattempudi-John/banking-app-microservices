package com.example.profileservice.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// The outbound half of InternalTokenFilter. This service is on both sides of the shared-secret
// change: it gates its own /api/v1/internal/ endpoints, and it CALLS one on auth-service
// (AuthServiceClient, GET and PUT /api/v1/internal/users/{userId}/phone-number). The moment
// auth-service starts enforcing, every identity-form submission here fails with a 401 unless this
// header goes out - so the two halves have to ship together.
//
// Registered as a plain @Bean rather than through @FeignClient(configuration = ...), matching how
// FeignErrorConfig next door is wired. A bean declared this way applies to every Feign client in
// this service, which is what we want: AuthServiceClient is the only one today, but a second client
// added later would otherwise start out silently unauthenticated, and the failure would surface as
// a 401 from a service someone else owns rather than as anything obviously missing here.
@Configuration
public class FeignInternalTokenConfig {

    // Same property and same default as InternalTokenFilter reads, so a single value configures
    // both directions - one place to rotate the secret, not two that can drift apart.
    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    private static final String TOKEN_HEADER = "X-Internal-Token";

    @Bean
    public RequestInterceptor internalTokenRequestInterceptor() {
        return template -> template.header(TOKEN_HEADER, internalToken);
    }
}
