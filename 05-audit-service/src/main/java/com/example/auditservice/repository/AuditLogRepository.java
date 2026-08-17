package com.example.auditservice.repository;

import com.example.auditservice.model.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persists audit rows.
 *
 * <p>Intentionally adds no methods of its own. The audit trail is append-only, so the only
 * operation the service uses is {@code save}, and that is only ever called with a brand-new entity —
 * {@link AuditLogEntity} has no setters and no updatable columns, so it cannot describe a change to
 * an existing row.
 *
 * <p>{@code JpaRepository} nevertheless inherits {@code delete} and {@code deleteAll}, which are the
 * one remaining way to destroy audit history from Java. Nothing calls them and nothing should; any
 * new method added here that mutates or removes rows breaks the guarantee the rest of the service is
 * built on.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {
}
