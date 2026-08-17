package com.example.accountservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import feign.RequestInterceptor;

/**
 * Attaches this service's shared secret to every outbound Feign call.
 *
 * <p>The outbound half of the internal-token scheme whose inbound half is {@code InternalTokenFilter}.
 * Peer services put the same filter in front of their own {@code /api/v1/internal/**} prefix, so
 * without this interceptor {@code ProfileServiceClient}'s KYC lookup returns 401,
 * {@code KycEnforcementAspect} fails closed by design, and every deposit and account opening in this
 * service starts refusing.
 *
 * <p>The secret is read from the same property and dev default as the inbound filter: in a
 * single-secret deployment, the credential this service accepts is the credential it presents.
 */
@Configuration
public class FeignInternalTokenConfig {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    /**
     * Supplies a global interceptor stamping {@code X-Internal-Token} on every Feign request.
     *
     * <p>Registered as a plain application-context bean rather than through
     * {@code @FeignClient(configuration = ...)}. That form builds an isolated child context per
     * client, so the bean would have to be repeated for each client added later, and forgetting it
     * surfaces as a silent 401 at runtime instead of a compile error. Declaring it globally means any
     * internal client this service grows is authenticated by default — the safe direction to fail.
     *
     * @return an interceptor applied to all Feign clients in this context, internal or not
     */
    @Bean
    public RequestInterceptor internalTokenRequestInterceptor() {
        return template -> template.header(INTERNAL_TOKEN_HEADER, internalToken);
    }
}
