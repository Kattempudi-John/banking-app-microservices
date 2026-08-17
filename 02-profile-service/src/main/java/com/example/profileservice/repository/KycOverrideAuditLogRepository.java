package com.example.profileservice.repository;

import com.example.profileservice.model.KycOverrideAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for {@link KycOverrideAuditLog}.
 *
 * <p>Append-only in practice: entries are saved when an admin overrides a KYC status and are never
 * updated or deleted, so the inherited mutating methods should not be used.
 */
@Repository
public interface KycOverrideAuditLogRepository extends JpaRepository<KycOverrideAuditLog, Long> {
}