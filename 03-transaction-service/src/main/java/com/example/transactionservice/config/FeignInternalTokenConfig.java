package com.example.transactionservice.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Attaches the shared internal secret to every outbound Feign call.
 *
 * <p>The outbound half of the contract {@code InternalTokenFilter} enforces inbound. All three
 * Feign clients in this module — account, profile, auth — call nothing but the siblings'
 * {@code /api/v1/internal/**} endpoints, so without this every transfer, KYC check and recipient
 * lookup starts returning 401 the moment those services enable their own filters.
 *
 * <p>Like the neighbouring {@code FeignErrorConfig}, this is a plain {@code @Configuration} that is
 * not named in any {@code @FeignClient(configuration = ...)} attribute, so it is picked up from the
 * application context and applies to every client at once. The per-client alternative was rejected
 * because it has to be remembered for each client added later, and a client that silently omits the
 * header is a production failure rather than a compile error.
 */
@Configuration
public class FeignInternalTokenConfig {

    private static final String TOKEN_HEADER = "X-Internal-Token";

    /**
     * Builds the interceptor that stamps {@code X-Internal-Token} onto every request.
     *
     * <p>Applied unconditionally rather than only to internal paths: every host this module has a
     * client for is a sibling service inside the cluster, so there is no external target the secret
     * could leak to, and a path check would only create a way for the header to be dropped from a
     * call that needs it. Adding a client that points outside the cluster would invalidate that
     * reasoning.
     *
     * <p>The secret is taken as a parameter rather than an injected field so the bean is a pure
     * function of its configured value and can be exercised against a bare request template without
     * an HTTP call.
     *
     * @param internalToken must match the value the receiving services read from the same property;
     *     defaults to a development value so local runs work unconfigured
     * @return an interceptor applied to every Feign client in the context
     */
    @Bean
    public RequestInterceptor internalTokenRequestInterceptor(
            @Value("${application.security.internal-token:local-dev-internal-token}") String internalToken) {
        return template -> template.header(TOKEN_HEADER, internalToken);
    }
}
