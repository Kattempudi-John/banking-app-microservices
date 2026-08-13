package com.example.transactionservice.exception;

import com.example.transactionservice.aspect.KycEnforcementAspect;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // Both handlers below answer with the same two keys on purpose. "error" is what this service
    // has always returned and what the frontend already reads; "message" is the key account-service
    // uses (via server.error.include-message). Sending both means one client-side helper can read
    // either service's failures without caring which one produced them.
    private static Map<String, String> body(String message) {
        return Map.of("error", message, "message", message);
    }

    @ExceptionHandler(KycEnforcementAspect.KycRequiredException.class)
    public ResponseEntity<Map<String, String>> handleKycRequired(KycEnforcementAspect.KycRequiredException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(ex.getMessage()));
    }

    // 503 rather than the 403 above: the caller isn't forbidden, we just couldn't reach the service
    // that knows. Nothing moved either way - the KYC gate stays fail-closed - but a retryable
    // outage answered as a 500 looks like a crash and as a 403 looks like a verdict on the user,
    // and it's neither.
    @ExceptionHandler(KycEnforcementAspect.KycStatusUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleKycStatusUnavailable(KycEnforcementAspect.KycStatusUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body(ex.getMessage()));
    }

    // Covers everything thrown by TransferService/ExternalWireService directly ("Invalid IBAN or
    // SWIFT code format.") as well as failures relayed from account-service, which FeignErrorConfig's
    // ErrorDecoder has already re-thrown locally as this same exception type carrying the original
    // reason. Handling it here keeps that reason attached to the response instead of letting Spring's
    // default error page reduce it to a bare status code.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String reason = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
        return ResponseEntity.status(ex.getStatusCode()).body(body(reason));
    }
}
