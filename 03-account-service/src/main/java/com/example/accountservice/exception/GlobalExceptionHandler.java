package com.example.accountservice.exception;

import com.example.accountservice.aspect.KycEnforcementAspect;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Translates the two KYC enforcement outcomes into HTTP responses.
 *
 * <p>Scope is deliberately narrow. Everything else this service throws — {@code ResponseStatusException},
 * {@code AccessDeniedException} — is left to Spring's default handling, because transaction-service's
 * {@code AccountServiceClient} parses those responses and intercepting them here would change the
 * shape of a contract other services already depend on.
 *
 * <p>Every body produced here carries the same text under both {@code error} and {@code message},
 * matching transaction-service's handler: the frontend's {@code extractApiError} reads {@code error},
 * while service-to-service callers read {@code message}. Dropping either key breaks one of the two.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Renders a refused-for-KYC outcome as {@code 403 Forbidden}.
     *
     * <p>403 rather than 503 is the meaningful half of the pair: the caller was positively determined
     * not to be verified, so retrying unchanged will keep failing and the user has to complete
     * identity verification first.
     *
     * @param ex its message is surfaced to the end user verbatim, so it must stay user-facing text
     * @return a body carrying that message under both {@code error} and {@code message}
     */
    @ExceptionHandler(KycEnforcementAspect.KycRequiredException.class)
    public ResponseEntity<Map<String, String>> handleKycRequired(KycEnforcementAspect.KycRequiredException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", ex.getMessage(), "message", ex.getMessage()));
    }

    /**
     * Renders an undetermined-KYC outcome as {@code 503 Service Unavailable}.
     *
     * <p>503 rather than the 403 above, and rather than the 500 this once returned: the caller is not
     * forbidden, we simply could not reach profile-service to find out. That is a temporary condition
     * on our side which a retry may resolve, and it matches what profile-service itself answers when
     * <em>its</em> dependency is down, so the whole chain reports one status for one situation.
     *
     * <p>The request was still refused. This handler only relabels an outcome that already failed
     * closed upstream; it never lets the operation through.
     *
     * @param ex its message is surfaced to the end user verbatim, so it must stay user-facing text
     * @return a body carrying that message under both {@code error} and {@code message}
     */
    @ExceptionHandler(KycEnforcementAspect.KycUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleKycUnavailable(KycEnforcementAspect.KycUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", ex.getMessage(), "message", ex.getMessage()));
    }
}
