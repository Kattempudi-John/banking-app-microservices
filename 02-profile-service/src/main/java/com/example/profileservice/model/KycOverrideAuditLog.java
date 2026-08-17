package com.example.profileservice.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * An immutable record of one administrator forcing a customer's KYC status.
 *
 * <p>Written whenever an admin overrides verification rather than letting the vendor decide, which
 * is why both statuses are kept: the row has to show what was changed away from, not only what it
 * became. There is no setter for any field — a corrected entry is a new row, so the trail cannot be
 * rewritten after the fact.
 */
@Entity
@Table(name = "kyc_override_audit_logs")
public class KycOverrideAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long adminId;

    @Enumerated(EnumType.STRING)
    private KycStatus oldStatus;

    @Enumerated(EnumType.STRING)
    private KycStatus newStatus;

    private String reason;
    private LocalDateTime timestamp;

    /**
     * Creates an empty instance for JPA.
     *
     * <p>Required by the persistence provider, which instantiates the entity through this
     * constructor and then populates the fields reflectively from the row. Application code should
     * use the five-argument constructor instead; an instance built here has no timestamp.
     */
    public KycOverrideAuditLog() {}

    /**
     * Creates a completed audit entry, stamping the current time as the moment of the override.
     *
     * <p>The timestamp is taken here rather than accepted from the caller, so an entry cannot be
     * backdated. The identifier is assigned by the database on save.
     *
     * @param userId the customer whose status was overridden, never {@code null}
     * @param adminId the administrator who performed it; this is the accountability field, so it
     *     must be a real admin id rather than a placeholder for a system action
     * @param oldStatus the status being replaced, so the row records what changed and not only the
     *     result
     * @param newStatus the status being forced
     * @param reason the justification; expected to be non-blank, as an override with no stated
     *     reason is what the trail exists to prevent
     */
    public KycOverrideAuditLog(Long userId, Long adminId, KycStatus oldStatus, KycStatus newStatus, String reason) {
        this.userId = userId;
        this.adminId = adminId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.reason = reason;
        this.timestamp = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getAdminId() { return adminId; }
    public KycStatus getOldStatus() { return oldStatus; }
    public KycStatus getNewStatus() { return newStatus; }
    public String getReason() { return reason; }
    public LocalDateTime getTimestamp() { return timestamp; }
}