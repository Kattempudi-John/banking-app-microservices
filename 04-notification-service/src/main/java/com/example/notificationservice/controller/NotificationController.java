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

// This service's first-ever REST endpoint - everything else here is Kafka-consumer-driven with no
// API surface. Powers the frontend's Notifications page.
@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("hasAuthority('SCOPE_FULL_AUTH')")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    public NotificationController(NotificationQueryService notificationQueryService) {
        this.notificationQueryService = notificationQueryService;
    }

    // Every filter is optional and they combine freely - the page lets a user narrow by kind, by
    // channel, by outcome and by date at the same time, so any subset of these five has to be a valid
    // request, including none of them at all. Supplying none returns exactly what this endpoint
    // returned before filtering existed, so the page keeps working untouched.
    //
    // from/to are inclusive on both ends and bind from ISO-8601 date-times (2026-08-01T00:00:00),
    // the same @DateTimeFormat contract account-service's transaction history already uses - so the
    // frontend sends one date format to both services rather than one per service.
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

    // Spring's own handling of an unparseable "?type=NOPE" is a bodyless response that tells the
    // caller nothing about which parameter it disliked or what it would have accepted. Local to this
    // controller rather than a @RestControllerAdvice on purpose: an advice would also catch type
    // mismatches on InternalNotificationController, whose callers are services parsing a different
    // response shape.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleUnparseableFilter(MethodArgumentTypeMismatchException ex) {
        String message = describeBadFilter(ex);
        // Both keys carry the same text, matching the other services' handlers: the frontend's
        // extractApiError reads "error", while "message" is what these services' own bodies use.
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", message, "message", message));
    }

    private String describeBadFilter(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName();
        Object value = ex.getValue();
        Class<?> required = ex.getRequiredType();

        if (required != null && required.isEnum()) {
            // Listing the accepted values is the whole point - the caller is one typo away from a
            // working request and the response should say which one.
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
        // The unfiltered feed - which is what the page loads with every time it opens - keeps the
        // exact derived query it has always used, rather than being rerouted through a Specification
        // that would build the identical single-predicate query. Same result either way; this way the
        // default request's behaviour is unchanged by the filtering feature rather than merely
        // believed to be.
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
