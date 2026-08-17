package com.example.notificationservice.model;

/**
 * What a stored notification was about.
 *
 * <p>One constant per writer: {@code TwoFactorEmailListener}, {@code TransactionAlertListener} and
 * {@code ProfileNotificationListener} each write exactly one kind, plus
 * {@code DailyBalanceSummaryJob}, the one writer that is not Kafka driven.
 *
 * <p>Backed by a real PostgreSQL enum type rather than a varchar, so adding a constant here is only
 * half the change — it also needs an {@code ALTER TYPE} migration (see V2 and V4) or Hibernate fails
 * to bind it.
 */
public enum NotificationType {

    /**
     * Retired, read-only. Nothing writes this since 2FA moved to email in V4, but existing rows are
     * real login history still served by {@code GET /api/v1/notifications}, so the constant must stay
     * for Hibernate to read them back.
     */
    SMS_2FA,
    EMAIL_2FA,
    TRANSACTION_ALERT,
    PROFILE_SECURITY,
    DAILY_SUMMARY
}
