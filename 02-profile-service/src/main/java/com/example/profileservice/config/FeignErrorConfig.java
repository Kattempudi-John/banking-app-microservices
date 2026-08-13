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

// Same decoder transaction-service uses. Without it Feign raises a generic FeignException for every
// non-2xx, which throws away the distinction that matters most here: a 409 from auth-service means
// "that number belongs to someone else" and must abort the identity submission, while a 400 means
// "that isn't a phone number" and has a message worth showing the user. Re-throwing as a
// ResponseStatusException keeps the status attached so ProfileManagementService can branch on it.
@Configuration
public class FeignErrorConfig {

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

    // auth-service's internal endpoints answer {"error": "..."}; Spring Boot's own default error
    // body uses {"message": "..."} instead, so both keys are read before falling back to the raw
    // HTTP reason phrase.
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
