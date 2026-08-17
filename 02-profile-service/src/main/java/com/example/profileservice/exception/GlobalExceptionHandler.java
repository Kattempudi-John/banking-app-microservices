package com.example.profileservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Renders this service's error responses so the reason attached to a failure survives to the client.
 *
 * <p>Spring's default error body carries only the status phrase, which discarded every explanation
 * this service writes: "That phone number is already registered to another account" reached the
 * browser as the bare word "Conflict", and the identity form's other rejections arrived as "Bad
 * Request". The status code was always right; the part that told the user what to do about it was
 * the part being dropped.
 *
 * <p>Every body here repeats the reason under both {@code error} and {@code message}. The frontend's
 * {@code extractApiError} reads {@code error} first and {@code message} second, while sibling
 * services disagree on which key they populate; filling both means this service reads correctly
 * through that one helper either way. Mirrors transaction-service's handler of the same name.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Answers with the exception's own status and its reason as the body text.
     *
     * @param ex a {@code null} reason falls back to the status phrase, so the body is never empty
     * @return the original status, never {@code 500}, with the reason under both {@code error} and
     *     {@code message}
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String reason = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();

        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", reason, "message", reason));
    }

    /**
     * Reports the first bean-validation failure on a request body as a {@code 400}.
     *
     * <p>Only the first field error is returned, not all of them joined: these forms are short and
     * each page shows a single message, so a concatenated list would repeat the same sentence for a
     * user who left several fields blank.
     *
     * @param ex messages come from the DTO constraints themselves, so the client is told which value
     *     is wrong and what would be right rather than just "Bad Request"
     * @return {@code 400} carrying that message under both {@code error} and {@code message}, or a
     *     generic phrase when no constraint supplied one
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationFailure(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse("Request validation failed");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message, "message", message));
    }
}
