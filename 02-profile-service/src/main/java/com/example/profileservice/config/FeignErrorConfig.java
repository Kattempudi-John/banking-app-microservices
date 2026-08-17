package com.example.profileservice.config;

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
 * Translates non-2xx Feign responses into {@link ResponseStatusException}, mirroring the decoder
 * transaction-service uses.
 *
 * <p>Feign's default raises an opaque {@code FeignException} for every failure, which discards the
 * distinction callers here depend on: a 409 from auth-service means the phone number belongs to
 * another account and must abort the identity submission, while a 400 means the input was not a
 * phone number and carries a message worth showing the user.
 */
@Configuration
public class FeignErrorConfig {

    /**
     * Supplies the decoder applied to every Feign client in this service.
     *
     * <p>Declared as a bare {@code @Bean} rather than per-client configuration, so a client added
     * later inherits it automatically. The upstream status is preserved on the thrown exception so
     * {@code ProfileManagementService} can branch on it; a status code Spring does not recognise
     * collapses to 500 rather than propagating an unmapped value.
     *
     * <p>The exception's reason is taken from the response body's {@code error} key, then its
     * {@code message} key, falling back to the raw HTTP reason phrase — auth-service's internal
     * endpoints answer with the former and Spring Boot's default error body with the latter.
     *
     * @return a decoder that always throws rather than returning a fallback response
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
            Object message = parsed.get("error");
            if (message == null) {
                message = parsed.get("message");
            }
            return message != null ? message.toString() : response.reason();
        } catch (IOException e) {
            return response.reason();
        }
    }
}
