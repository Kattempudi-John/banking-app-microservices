package com.example.notificationservice.model;

// mirrors the three Kafka listeners that persist a record - TwoFactorSmsListener, TransactionAlertListener,
// and ProfileNotificationListener each write exactly one of these.
public enum NotificationType {
    SMS_2FA,
    TRANSACTION_ALERT,
    PROFILE_SECURITY
}
