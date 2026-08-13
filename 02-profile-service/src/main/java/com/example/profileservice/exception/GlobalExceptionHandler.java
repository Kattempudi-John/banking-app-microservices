package com.example.profileservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

// Without this, every reason this service attaches to a ResponseStatusException was thrown away.
// Spring's default error body carries only the status phrase, so "That phone number is already
// registered to another account" reached the browser as the bare word "Conflict", and the identity
// form's other rejections - an unresolvable phone number, an applicant under 18 - arrived as
// "Bad Request". The status code was right in every case; the one part that told the user what to
// do about it was the part being dropped.
//
// Mirrors transaction-service's handler of the same name, including sending the reason under both
// keys: the frontend's extractApiError reads "error" first and "message" second, and account-service
// answers with "message" (via server.error.include-message) while transaction-service uses "error".
// Populating both means this service reads correctly through that same helper either way.
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String reason = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();

        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", reason, "message", reason));
    }

    // The same problem one layer over: a @Valid failure was still answering with Spring's default
    // error body, whose "error" key holds the status phrase - so every message written on a DTO
    // constraint ("Daily summary hour must be between 0 and 23") reached the browser as the bare
    // words "Bad Request", and the user was told a value was wrong without being told which one or
    // what would be right. Reported under the same two keys as above, for the same reason.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationFailure(MethodArgumentNotValidException ex) {
        // The first field error, not all of them concatenated: these forms are short and the pages
        // show one message, so a joined list would just be the same sentence repeated for a user
        // who left several fields blank.
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse("Request validation failed");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message, "message", message));
    }
}
