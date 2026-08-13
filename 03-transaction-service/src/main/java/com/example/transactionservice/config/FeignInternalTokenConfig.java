package com.example.transactionservice.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// The outbound half of the shared-secret contract InternalTokenFilter enforces inbound. Every
// sibling service is adding the same filter in front of its own /api/v1/internal/** endpoints, and
// all three Feign clients in this module (account, profile, auth) call nothing but those endpoints -
// so without this, every transfer, KYC check and recipient lookup starts 401ing the moment the
// other services turn their filters on.
//
// A plain @Configuration with a @Bean, exactly like the neighbouring FeignErrorConfig: neither is
// named in any @FeignClient(configuration = ...) attribute, which means both are picked up from the
// application context and apply to every Feign client at once. Rejected the per-client alternative
// (a non-@Configuration class referenced from each @FeignClient) precisely because it would need
// remembering on the next client added, and a client that silently forgets the header is a broken
// call in production rather than a compile error here.
@Configuration
public class FeignInternalTokenConfig {

    private static final String TOKEN_HEADER = "X-Internal-Token";

    // Taken as a method parameter rather than an injected field so the bean is a pure function of
    // its configured value - the test can build it, or pull it from the context, and apply it to a
    // bare RequestTemplate without standing up an HTTP call.
    @Bean
    public RequestInterceptor internalTokenRequestInterceptor(
            @Value("${application.security.internal-token:local-dev-internal-token}") String internalToken) {
        // Applied unconditionally rather than only to /api/v1/internal/ paths: every target this
        // module has a Feign client for is a sibling service inside the cluster, so there is no
        // outside host the secret could travel to. A path check here would only add a way to get
        // the header silently dropped on a call that needs it.
        return template -> template.header(TOKEN_HEADER, internalToken);
    }
}
