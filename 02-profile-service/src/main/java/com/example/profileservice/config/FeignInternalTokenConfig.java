package com.example.profileservice.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Attaches the shared internal-service token to outbound Feign calls.
 *
 * <p>The outbound half of {@code InternalTokenFilter}: this service sits on both sides of the
 * shared-secret scheme, gating its own {@code /api/v1/internal/} endpoints and calling auth-service's
 * ({@code AuthServiceClient}, {@code GET} and {@code PUT}
 * {@code /api/v1/internal/users/{userId}/phone-number}). Once auth-service enforces the token, every
 * identity-form submission here fails with a 401 unless this header goes out, so the two halves must
 * ship together.
 *
 * <p>The token is read from {@code application.security.internal-token} — the same property and
 * default {@code InternalTokenFilter} reads, so one value configures both directions and the secret
 * has a single place to be rotated rather than two that can drift apart.
 */
@Configuration
public class FeignInternalTokenConfig {

    @Value("${application.security.internal-token:local-dev-internal-token}")
    private String internalToken;

    private static final String TOKEN_HEADER = "X-Internal-Token";

    /**
     * Supplies the interceptor that stamps {@code X-Internal-Token} onto every outbound request.
     *
     * <p>Declared as a bare {@code @Bean} rather than through
     * {@code @FeignClient(configuration = ...)}, matching {@code FeignErrorConfig}. Registered this
     * way it applies to every Feign client in the service, which is the point: a client added later
     * would otherwise start out silently unauthenticated, and the failure would surface as a 401 from
     * a service someone else owns rather than as anything visibly missing here.
     *
     * @return an interceptor that sets the header unconditionally, including on calls to endpoints
     *     that do not require it
     */
    @Bean
    public RequestInterceptor internalTokenRequestInterceptor() {
        return template -> template.header(TOKEN_HEADER, internalToken);
    }
}
