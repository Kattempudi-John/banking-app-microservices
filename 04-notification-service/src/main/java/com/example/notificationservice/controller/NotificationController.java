package com.example.notificationservice.controller;

import com.example.notificationservice.dto.NotificationResponseDto;
import com.example.notificationservice.model.NotificationRecord;
import com.example.notificationservice.repository.NotificationRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping
    public ResponseEntity<Page<NotificationResponseDto>> getNotifications(
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Long userId = extractUserIdFromAuth();

        return ResponseEntity.ok(notificationQueryService.getNotifications(userId, pageable));
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

    Page<NotificationResponseDto> getNotifications(Long userId, Pageable pageable) {
        return notificationRecordRepository.findByUserId(userId, pageable).map(this::toDto);
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
