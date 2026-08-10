package com.example.notificationservice.dto;

import java.time.LocalDateTime;

public record NotificationResponseDto(
        Long id,
        String type,
        String channel,
        String subject,
        String message,
        String status,
        LocalDateTime createdAt
) {
}
