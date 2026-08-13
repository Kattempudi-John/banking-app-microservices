package com.example.notificationservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.notificationservice.security.InternalTokenFilter;

import feign.RequestInterceptor;

// The outbound half of the internal-token contract. InternalTokenFilter guards what comes IN;
// this attaches the same token to what goes OUT, because this service is also a caller: every
// endpoint ProfileServiceClient and AccountServiceClient reach sits under /api/v1/internal/ in
// profile-service and account-service, and those services are adding the identical filter.
// Without this bean the daily balance summary keeps compiling, keeps running, and silently
// delivers nothing the moment they start enforcing - the job would just see 401s from both
// downstreams and log a per-timezone failure nobody is watching for.
@Configuration
public class FeignInternalTokenConfig {

    // Same property and same dev default as InternalTokenFilter reads, so a single override
    // configures both directions. A service whose inbound and outbound tokens disagree can still
    // call out fine while rejecting everyone calling in, which is a confusing way to fail.
    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    // Registered as a plain @Bean rather than through a @FeignClient(configuration = ...) class,
    // which is the point: a bare RequestInterceptor bean is picked up by Feign's global context and
    // applies to EVERY client in this service. Scoping it per-client would mean remembering to add
    // it to each new @FeignClient, and the failure mode of forgetting is a silent 401 at runtime
    // rather than anything the compiler or startup would catch.
    @Bean
    public RequestInterceptor internalTokenRequestInterceptor() {
        return template -> template.header(InternalTokenFilter.INTERNAL_TOKEN_HEADER, internalToken);
    }
}
