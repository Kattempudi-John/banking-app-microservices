package com.example.transactionservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boot smoke test for transaction-service: it starts the full application context and asserts
 * nothing. The startup is the assertion — a bean that cannot be constructed, an unresolvable
 * {@code @Value}, or a circular dependency fails the test before the empty method body runs.
 *
 * <h2>What is real here</h2>
 *
 * <p>Nothing is mocked, deliberately: the transfer and external-wire services, the Feign client
 * onto account-service, the security filters and both {@code KafkaTemplate} beans are all built for
 * real. The larger suites in this module mock collaborators to isolate behaviour; this one exists
 * precisely to prove the un-mocked wiring holds together.
 *
 * <h2>Test configuration</h2>
 *
 * <p>{@code src/test/resources/application.properties} overrides the datasource with H2 in
 * PostgreSQL mode ({@code ddl-auto=none}, Flyway disabled), so the test never reaches the shared
 * docker-compose Postgres named in {@code src/main/resources/application.properties} and never runs
 * a migration — which also side-steps the interleaved-version Flyway history this module shares with
 * account-service. No table is created, which is fine because context startup builds repository
 * proxies and validates entity metadata without issuing a query.
 *
 * <p>Kafka is not excluded and not mocked. A {@code KafkaTemplate} is constructed lazily and does
 * not contact a broker at startup, so the context comes up with no {@code localhost:9092} listening;
 * this proves the producer beans and their serializer configuration are constructible, not that
 * anything can actually be published.
 *
 * <p>{@code @SpringBootTest} rather than a slice annotation is the point: a slice would load a
 * subset of beans and prove nothing about whether the application as a whole starts.
 *
 * <p>No fixtures, no {@code @BeforeEach}, no shared state.
 */
@SpringBootTest
class ApplicationTests {

	@Test
	void applicationContext_transactionServiceConfiguration_startsWithAllBeansWired() {
	}

}
