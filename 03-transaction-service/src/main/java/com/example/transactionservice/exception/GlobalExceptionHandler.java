package com.example.transactionservice.exception;

import com.example.transactionservice.aspect.KycEnforcementAspect;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Translates the service's exceptions into JSON error responses.
 *
 * <p>Every response body carries the same text under two keys, {@code error} and {@code message}.
 * {@code error} is what this service has always returned and what the frontend reads;
 * {@code message} is the key account-service produces via {@code server.error.include-message}.
 * Emitting both lets one client-side helper read either service's failures without knowing which
 * produced them, so dropping either key breaks a consumer.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static Map<String, String> body(String message) {
        return Map.of("error", message, "message", message);
    }

    /**
     * Answers 403 when the KYC gate refused an unverified customer.
     *
     * <p>A verdict on the caller, not a transient failure: retrying without completing verification
     * gets the same answer.
     *
     * @param ex its message is passed through to the customer, so it must stay user-facing
     * @return a 403 carrying the refusal reason
     */
    @ExceptionHandler(KycEnforcementAspect.KycRequiredException.class)
    public ResponseEntity<Map<String, String>> handleKycRequired(KycEnforcementAspect.KycRequiredException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(ex.getMessage()));
    }

    /**
     * Answers 503 when the KYC status could not be read at all.
     *
     * <p>Deliberately not the 403 above and not a 500: the caller is not forbidden, the service that
     * knows was simply unreachable. The gate stays fail-closed either way and nothing moved, but a
     * retryable outage reported as 500 reads as a crash and as 403 reads as a verdict on the
     * customer, and it is neither. The status is the signal that the call is worth retrying.
     *
     * @param ex its message is passed through to the caller
     * @return a 503 carrying the failure reason
     */
    @ExceptionHandler(KycEnforcementAspect.KycStatusUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleKycStatusUnavailable(KycEnforcementAspect.KycStatusUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body(ex.getMessage()));
    }

    /**
     * Relays any {@link ResponseStatusException} with its status and reason intact.
     *
     * <p>Covers both what the transfer services throw directly, such as a rejected IBAN or SWIFT
     * format, and failures originating in account-service: {@code FeignErrorConfig}'s decoder has
     * already rethrown those locally as this same type carrying the remote reason. Handling it here
     * keeps that reason on the response instead of letting Spring's default error page reduce it to
     * a bare status code.
     *
     * <p>Falls back to the status code's own text when the exception carries no reason, so the body
     * is never empty.
     *
     * @param ex its status is used verbatim, so a relayed downstream 400 stays a 400 to the customer
     * @return a response mirroring the exception's status
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String reason = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
        return ResponseEntity.status(ex.getStatusCode()).body(body(reason));
    }
}
