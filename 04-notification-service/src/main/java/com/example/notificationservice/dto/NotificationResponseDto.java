package com.example.notificationservice.dto;

import java.time.LocalDateTime;

/**
 * One notification as the frontend's Notifications page receives it.
 *
 * <p>The three enum-backed fields are carried as {@code String} rather than the enums themselves so
 * a constant added here does not force a frontend release to render existing rows.
 *
 * @param id the persisted record id, never {@code null} on a response
 * @param type name of a {@code NotificationType} constant, including retired ones such as
 *     {@code SMS_2FA} still present on historical rows
 * @param channel name of a {@code NotificationChannel} constant
 * @param subject may be {@code null}; the column is nullable
 * @param message the stored body, never {@code null}; deliberately never contains a live 2FA code
 * @param status name of a {@code NotificationStatus} constant — {@code FAILED} rows are returned
 *     alongside sent ones, since a summary that could not be delivered is exactly what the feed
 *     exists to show
 * @param createdAt when the record was written, never {@code null}
 */
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
