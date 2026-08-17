package com.example.accountservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boot smoke test for account-service: it starts the full application context and asserts nothing.
 * The startup is the assertion — a bean that cannot be constructed, an unresolvable
 * {@code @Value}, or a circular dependency fails the test before the empty method body runs.
 *
 * <h2>What is real here</h2>
 *
 * <p>Nothing is mocked, deliberately: every controller, service, Feign client, security filter and
 * JPA repository is instantiated for real, which is the only thing this test is for. The larger
 * suites in this module mock repositories to isolate behaviour; this one exists precisely to prove
 * the un-mocked wiring holds together.
 *
 * <h2>Test configuration</h2>
 *
 * <p>{@code src/test/resources/application.properties} overrides the datasource with H2 in
 * PostgreSQL mode ({@code ddl-auto=none}, Flyway disabled), so the test never reaches the shared
 * docker-compose Postgres named in {@code src/main/resources/application.yml} and never runs a
 * migration. No table is created, which is fine because context startup builds repository proxies
 * and validates entity metadata without issuing a query.
 *
 * <p>Kafka is not excluded and not mocked: {@code UserRegisteredListener}'s container is created
 * and left to retry its connection to {@code localhost:9092} in the background, which does not fail
 * context startup. So this test proves the listener bean exists and is constructible — it does not
 * prove it can reach a broker or consume anything.
 *
 * <p>{@code @SpringBootTest} rather than a slice annotation is the point: a slice would load a
 * subset of beans and prove nothing about whether the application as a whole starts.
 *
 * <p>No fixtures, no {@code @BeforeEach}, no shared state.
 */
@SpringBootTest
class AccountServiceApplicationTests {

	@Test
	void applicationContext_accountServiceConfiguration_startsWithAllBeansWired() {
	}

}
