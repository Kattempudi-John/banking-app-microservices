package com.example.auditservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the audit service, an append-only recorder of profile changes.
 *
 * <p>The service has no REST API of its own — no controllers and no security configuration — and
 * exposes nothing to callers. Its entire behaviour is
 * {@link com.example.auditservice.service.ProfileAuditListener} consuming the {@code profile-events}
 * Kafka topic and writing one immutable row per event.
 *
 * <p>Append-only is enforced in three independent layers, and all three have to stay that way for
 * the audit trail to be worth anything: {@link com.example.auditservice.model.AuditLogEntity} has no
 * setters, every one of its columns is mapped {@code updatable = false}, and no update or delete SQL
 * exists anywhere in the service or its repository.
 */
@SpringBootApplication
public class AuditServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuditServiceApplication.class, args);
	}

}
