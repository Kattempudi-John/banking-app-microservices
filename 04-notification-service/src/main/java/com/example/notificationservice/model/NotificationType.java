package com.example.notificationservice.model;

// mirrors the three Kafka listeners that persist a record - TwoFactorSmsListener, TransactionAlertListener,
// and ProfileNotificationListener each write exactly one of these - plus DailyBalanceSummaryJob, which
// is the one writer that isn't Kafka-driven.
//
// These are backed by a real PostgreSQL enum type, not a varchar, so adding a constant here is only
// half the change: it also needs an ALTER TYPE migration (see V2) or Hibernate fails to bind it.
public enum NotificationType {
    SMS_2FA,
    TRANSACTION_ALERT,
    PROFILE_SECURITY,
    DAILY_SUMMARY
}
