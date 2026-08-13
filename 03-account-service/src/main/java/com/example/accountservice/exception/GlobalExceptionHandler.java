package com.example.accountservice.exception;

import com.example.accountservice.aspect.KycEnforcementAspect;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

// Only the two KYC outcomes are handled here. Everything else this service throws
// (ResponseStatusException, AccessDeniedException) is deliberately left to Spring's default
// handling, because transaction-service's AccountServiceClient parses those responses and
// intercepting them would change the shape of a contract other services already depend on.
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(KycEnforcementAspect.KycRequiredException.class)
    public ResponseEntity<Map<String, String>> handleKycRequired(KycEnforcementAspect.KycRequiredException ex) {
        // Both keys carry the same text, matching transaction-service's handler: the frontend's
        // extractApiError reads "error", while "message" is what this service's own responses use.
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", ex.getMessage(), "message", ex.getMessage()));
    }

    // 503 rather than the 403 above, and rather than the 500 this used to be. The user is not
    // forbidden - we simply could not reach profile-service to find out, which is a temporary
    // condition on our side that a retry may fix. It also matches what profile-service itself
    // answers when ITS dependency is down, so the whole chain reports one status for one situation.
    // The request was still refused: this handler only relabels an already fail-closed outcome.
    @ExceptionHandler(KycEnforcementAspect.KycUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleKycUnavailable(KycEnforcementAspect.KycUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", ex.getMessage(), "message", ex.getMessage()));
    }
}
