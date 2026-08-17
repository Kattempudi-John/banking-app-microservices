package com.example.auditservice.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One immutable audit row recording a single profile change.
 *
 * <p>Rows are append-only and the immutability is enforced three times over, deliberately: this
 * class exposes no setters, every column below is mapped {@code updatable = false} so Hibernate
 * omits them from any UPDATE it might generate, and no update or delete SQL exists in the service.
 * Removing any one of those layers silently makes the audit trail editable.
 *
 * <p>{@code timestamp} is when this service processed the event, not when the change happened. The
 * incoming event's own timestamp is not read, so the value lags the real change by the Kafka and
 * processing delay and does not survive a replay of the topic — do not treat it as the moment of
 * the change itself.
 */
@Entity
@Table(name = "profile_audit_logs")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private LocalDateTime timestamp;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "changed_fields_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String changedFieldsJson;

    /**
     * Exists only for JPA, which instantiates the entity reflectively before populating its fields
     * from the row.
     *
     * <p>It leaves every field {@code null} and no setter can fill them in, so application code must
     * use {@link #AuditLogEntity(LocalDateTime, Long, String, String)} instead — an instance built
     * here and saved would fail the {@code NOT NULL} constraints.
     */
    public AuditLogEntity() {}

    /**
     * Builds a complete audit row; the only way application code can create one.
     *
     * @param timestamp when the event was processed, not when the change occurred; non-null
     * @param userId the subject of the change, non-null
     * @param eventType the producer's event name, stored as free text with no enum validating it
     * @param changedFieldsJson the changed fields as a serialised JSON string, non-null; stored in a
     *     {@code TEXT} column, so it is opaque to SQL and cannot be queried field by field
     */
    public AuditLogEntity(LocalDateTime timestamp, Long userId, String eventType, String changedFieldsJson) {
        this.timestamp = timestamp;
        this.userId = userId;
        this.eventType = eventType;
        this.changedFieldsJson = changedFieldsJson;
    }

    public Long getId() { return id; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public Long getUserId() { return userId; }
    public String getEventType() { return eventType; }
    public String getChangedFieldsJson() { return changedFieldsJson; }
}