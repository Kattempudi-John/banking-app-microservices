package com.example.transactionservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Response;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

/**
 * Turns non-2xx Feign responses into local Spring exceptions.
 *
 * <p>Not named in any {@code @FeignClient(configuration = ...)} attribute, so it is picked up from
 * the application context and applies to every Feign client in this module at once.
 */
@Configuration
public class FeignErrorConfig {

    /**
     * Builds the decoder that rethrows a downstream failure as a {@link ResponseStatusException}.
     *
     * <p>The remote status is preserved so a 400 from account-service stays a 400 to the customer
     * rather than becoming a 500; an unrecognised status code degrades to 500. The remote
     * explanation is lifted from the {@code message} key of Spring Boot's default error body, which
     * only exists while account-service keeps {@code server.error.include-message=always} set — with
     * that turned off, or a body that is absent or not JSON, the response falls back to the bare
     * HTTP reason phrase instead of failing. {@code GlobalExceptionHandler} then relays whichever
     * of the two reached it, so the original reason survives to the caller.
     *
     * @return a decoder that never returns a retryable exception, so Feign does not retry
     */
    @Bean
    public ErrorDecoder errorDecoder() {
        return (methodKey, response) -> {
            HttpStatus status = HttpStatus.resolve(response.status());
            if (status == null) {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            return new ResponseStatusException(status, extractMessage(response));
        };
    }

    private String extractMessage(Response response) {
        if (response.body() == null) {
            return response.reason();
        }
        try (var body = response.body().asInputStream()) {
            Map<?, ?> parsed = new ObjectMapper().readValue(body, Map.class);
            Object message = parsed.get("message");
            return message != null ? message.toString() : response.reason();
        } catch (IOException e) {
            return response.reason();
        }
    }
}
