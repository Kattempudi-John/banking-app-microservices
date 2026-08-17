package com.example.notificationservice.model;

// mirrors the three Kafka listeners that persist a record - TwoFactorEmailListener, TransactionAlertListener,
// and ProfileNotificationListener each write exactly one of these - plus DailyBalanceSummaryJob, which
// is the one writer that isn't Kafka-driven.
//
// These are backed by a real PostgreSQL enum type, not a varchar, so adding a constant here is only
// half the change: it also needs an ALTER TYPE migration (see V2 and V4) or Hibernate fails to bind it.
public enum NotificationType {
    // Nothing writes SMS_2FA any more - 2FA moved to email in V4 - but rows carrying it are real
    // login history and are still served by GET /api/v1/notifications, so the constant has to stay
    // for Hibernate to read them back.
    SMS_2FA,
    EMAIL_2FA,
    TRANSACTION_ALERT,
    PROFILE_SECURITY,
    DAILY_SUMMARY
}
