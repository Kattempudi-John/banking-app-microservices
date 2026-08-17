package com.example.auditservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boot smoke test for audit-service: it starts the full application context and asserts nothing.
 * The startup itself is the assertion — a bean that cannot be constructed, a missing property, or a
 * circular dependency fails the test before the empty method body is ever reached.
 *
 * <h2>What is real here</h2>
 *
 * <p>Nothing is mocked. Unlike {@link AuditServiceTestSuite}, which replaces
 * {@code AuditLogRepository} with a {@code @MockBean}, this class boots every bean for real,
 * including the JPA repository proxy and the {@code @KafkaListener} container. Because the mock
 * changes the context cache key, the two classes get two separate application contexts rather than
 * sharing one, so this is a genuinely independent check that the production wiring stands up on its
 * own.
 *
 * <h2>Test configuration</h2>
 *
 * <p>{@code src/test/resources/application.properties} points the datasource at H2 in PostgreSQL
 * mode with {@code ddl-auto=none} and Flyway disabled, so no real Postgres is required and no schema
 * is created. That is sufficient because context startup only builds the repository proxy and
 * validates the entity metadata; it never issues a query, so the absent
 * {@code profile_audit_logs} table is never noticed.
 *
 * <p>{@code @SpringBootTest} rather than a slice annotation is the point of the test: a slice would
 * load a subset of beans and prove nothing about whether the application as a whole can start.
 *
 * <p>No fixtures, no {@code @BeforeEach}, no shared state.
 */
@SpringBootTest
class AuditServiceApplicationTests {

	@Test
	void applicationContext_auditServiceConfiguration_startsWithAllBeansWired() {
	}

}
