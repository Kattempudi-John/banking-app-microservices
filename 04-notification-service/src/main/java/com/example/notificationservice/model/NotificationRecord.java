package com.example.notificationservice.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The durable trace of a dispatched or failed notification, and the sole backing of
 * {@code GET /api/v1/notifications}.
 *
 * <p>Written by each Kafka listener and by the daily summary job immediately after calling
 * {@code NotificationProviderService}, recording success and failure alike.
 *
 * <p>{@code type}, {@code channel} and {@code status} map to real PostgreSQL enum types declared in
 * {@code V1__Create_Notification_Records_Table.sql}, not varchar columns. {@code @Enumerated(STRING)}
 * alone makes Hibernate bind a plain varchar, which Postgres refuses to compare against an enum
 * column; {@code @JdbcTypeCode(SqlTypes.NAMED_ENUM)} is what binds the value as the named enum type
 * instead. That is also why the filtered feed uses a {@code Specification} rather than an
 * "IS NULL OR" query — there is no meaningful cast for an unused enum-typed bind.
 *
 * <p>{@code message} is a {@code TEXT} column rather than the default {@code varchar(255)} because
 * alert and summary bodies are full HTML documents. It is served straight back to the user, which is
 * why the 2FA listener stores a masked line here instead of the mail it actually sent.
 *
 * <p>{@code createdAt} is set on first persist if unset and is never updated afterwards.
 */
@Entity
@Table(name = "notification_records")
public class NotificationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Column
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public NotificationRecord() {}

    /**
     * Stamps the creation time before the first insert.
     *
     * <p>Only fills in a {@code null} value, so a caller that set {@code createdAt} explicitly — a
     * backfill or a test asserting on ordering — keeps the instant it chose.
     */
    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public NotificationType getType() { return type; }
    public void setType(NotificationType type) { this.type = type; }
    public NotificationChannel getChannel() { return channel; }
    public void setChannel(NotificationChannel channel) { this.channel = channel; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public NotificationStatus getStatus() { return status; }
    public void setStatus(NotificationStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
