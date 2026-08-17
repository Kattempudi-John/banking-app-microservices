package com.example.notificationservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.notificationservice.security.InternalTokenFilter;

import feign.RequestInterceptor;

/**
 * Supplies the outbound half of the internal-token contract.
 *
 * <p>{@code InternalTokenFilter} guards what comes in; this attaches the same token to what goes
 * out, because this service is also a caller — every endpoint {@code ProfileServiceClient} and
 * {@code AccountServiceClient} reach sits under {@code /api/v1/internal/} in profile-service and
 * account-service, and those services enforce the identical filter. Without this configuration the
 * daily balance summary still compiles and still runs, but delivers nothing: both downstreams
 * answer 401 and the job logs a failure nobody is watching for.
 *
 * <p>The token is read from {@code application.security.internal-token} with the same dev default
 * {@code InternalTokenFilter} uses, so one override configures both directions. A service whose
 * inbound and outbound tokens disagree calls out fine while rejecting everyone calling in.
 */
@Configuration
public class FeignInternalTokenConfig {

    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    /**
     * Stamps the internal-token header onto every outbound Feign request from this service.
     *
     * <p>Declared as a bare {@code RequestInterceptor} bean rather than through a
     * {@code @FeignClient(configuration = ...)} class deliberately: a global bean is picked up by
     * Feign's parent context and applies to every client, present and future. Scoping it per client
     * would mean remembering to wire it into each new {@code @FeignClient}, and forgetting produces
     * a silent runtime 401 rather than anything the compiler or startup catches.
     *
     * @return an interceptor that sets {@code InternalTokenFilter.INTERNAL_TOKEN_HEADER}; the same
     *     constant the inbound filter checks, so the two cannot drift apart
     */
    @Bean
    public RequestInterceptor internalTokenRequestInterceptor() {
        return template -> template.header(InternalTokenFilter.INTERNAL_TOKEN_HEADER, internalToken);
    }
}
