package com.example.notificationservice.model;

/**
 * How a notification was delivered.
 *
 * <p>Backed by a real PostgreSQL enum type rather than a varchar, so adding a constant here needs an
 * accompanying {@code ALTER TYPE} migration or Hibernate fails to bind it.
 *
 * <p>{@code SMS} is retained for historical rows only — nothing dispatches SMS since 2FA moved to
 * email.
 */
public enum NotificationChannel {
    SMS,
    EMAIL
}
