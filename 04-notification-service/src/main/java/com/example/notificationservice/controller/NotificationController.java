package com.example.notificationservice.controller;

import com.example.notificationservice.dto.NotificationFilter;
import com.example.notificationservice.dto.NotificationResponseDto;
import com.example.notificationservice.model.NotificationChannel;
import com.example.notificationservice.model.NotificationRecord;
import com.example.notificationservice.model.NotificationStatus;
import com.example.notificationservice.model.NotificationType;
import com.example.notificationservice.repository.NotificationRecordRepository;
import com.example.notificationservice.repository.NotificationSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Serves the signed-in user's own notification feed, which powers the frontend's Notifications page.
 *
 * <p>This is the only customer-facing HTTP surface in the service; everything else here is Kafka
 * consumer driven. Every method requires the {@code FULL_AUTH} scope, so a token issued mid-2FA
 * cannot read the feed.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("hasAuthority('SCOPE_FULL_AUTH')")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    public NotificationController(NotificationQueryService notificationQueryService) {
        this.notificationQueryService = notificationQueryService;
    }

    /**
     * Returns a page of the calling user's notifications, newest first.
     *
     * <p>The user id comes from the {@code userId} claim on the caller's JWT and never from a
     * request parameter, so no combination of filters can widen the result past the caller's own
     * records. There is no way to request another user's feed through this endpoint.
     *
     * <p>All five filters are optional and combine freely; supplying none returns the unfiltered
     * feed. An empty result is an empty page rather than a 404 — a user with no notifications is
     * normal, not an error.
     *
     * @param type optional; must name a {@code NotificationType} constant, else 400 listing the
     *     accepted values
     * @param channel optional; must name a {@code NotificationChannel} constant, else 400 listing
     *     the accepted values
     * @param status optional; must name a {@code NotificationStatus} constant, else 400 listing the
     *     accepted values
     * @param from optional lower bound, inclusive; ISO-8601 local date-time such as
     *     {@code 2026-08-01T00:00:00}, the same format account-service's transaction history accepts
     * @param to optional upper bound, also inclusive, so a notification created exactly on the
     *     boundary is returned rather than dropped
     * @param pageable defaults to 50 records sorted by {@code createdAt} descending when the caller
     *     supplies no paging parameters
     * @return a page of the caller's own notifications, empty when nothing matches
     */
    @GetMapping
    public ResponseEntity<Page<NotificationResponseDto>> getNotifications(
            @RequestParam(required = false) NotificationType type,
            @RequestParam(required = false) NotificationChannel channel,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Long userId = extractUserIdFromAuth();

        return ResponseEntity.ok(notificationQueryService.getNotifications(
                userId, new NotificationFilter(type, channel, status, from, to), pageable));
    }

    /**
     * Translates an unparseable filter value into a 400 that names the parameter and its accepted
     * values.
     *
     * <p>Spring's own handling of {@code ?type=NOPE} is a bodyless response saying neither which
     * parameter it disliked nor what it would have taken. Scoped to this controller rather than
     * declared as a {@code @RestControllerAdvice} deliberately: an advice would also catch type
     * mismatches on {@code InternalNotificationController}, whose callers are services parsing a
     * different response shape.
     *
     * @param ex the mismatch Spring raised while binding a query parameter
     * @return 400 whose body repeats the same text under both {@code error} and {@code message} —
     *     the frontend's {@code extractApiError} reads {@code error}, while {@code message} is the
     *     key these services' own bodies use
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleUnparseableFilter(MethodArgumentTypeMismatchException ex) {
        String message = describeBadFilter(ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", message, "message", message));
    }

    private String describeBadFilter(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName();
        Object value = ex.getValue();
        Class<?> required = ex.getRequiredType();

        if (required != null && required.isEnum()) {
            String allowed = Arrays.stream(required.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            return "Invalid value '%s' for filter '%s'. Allowed values: %s.".formatted(value, name, allowed);
        }
        if (required == LocalDateTime.class) {
            return "Invalid value '%s' for filter '%s'. Expected an ISO date-time, for example 2026-08-01T00:00:00."
                    .formatted(value, name);
        }
        return "Invalid value '%s' for parameter '%s'.".formatted(value, name);
    }

    private Long extractUserIdFromAuth() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new SecurityException("User is not authenticated");
        }
        if (!authentication.isAuthenticated()) {
            throw new SecurityException("User is not authenticated");
        }
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getClaim("userId");
    }
}

@Service
class NotificationQueryService {

    private final NotificationRecordRepository notificationRecordRepository;

    NotificationQueryService(NotificationRecordRepository notificationRecordRepository) {
        this.notificationRecordRepository = notificationRecordRepository;
    }

    Page<NotificationResponseDto> getNotifications(Long userId, NotificationFilter filter, Pageable pageable) {
        Page<NotificationRecord> page = filter.isEmpty()
                ? notificationRecordRepository.findByUserId(userId, pageable)
                : notificationRecordRepository.findAll(NotificationSpecifications.forUser(userId, filter), pageable);

        return page.map(this::toDto);
    }

    private NotificationResponseDto toDto(NotificationRecord record) {
        return new NotificationResponseDto(
                record.getId(),
                record.getType().name(),
                record.getChannel().name(),
                record.getSubject(),
                record.getMessage(),
                record.getStatus().name(),
                record.getCreatedAt()
        );
    }
}
