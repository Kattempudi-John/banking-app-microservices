package com.example.profileservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Boot smoke test for profile-service: it starts the full application context and asserts nothing.
 * The startup is the assertion — a bean that cannot be constructed, an unresolvable {@code @Value},
 * or a circular dependency fails the test before the empty method body runs.
 *
 * <h2>What is real and what is mocked</h2>
 *
 * <p>Every profile, KYC and security bean is built for real. The single {@code @MockBean} is
 * {@link KafkaTemplate}, and it is there for wiring reasons rather than behavioural ones: the
 * properties below exclude {@code KafkaAutoConfiguration}, which removes Spring Boot's
 * auto-configured template, so the services that inject one would fail to start with no candidate
 * bean. The mock supplies that candidate. The pair — exclude the auto-configuration, then hand back
 * a mock — is what lets this context start with no broker anywhere near it.
 *
 * <h2>Test configuration</h2>
 *
 * <p>The {@code @TestPropertySource} does three separate jobs:
 *
 * <ul>
 *   <li>points the datasource at an in-memory H2 with {@code ddl-auto=create-drop}, so the schema is
 *       built from the entities at startup and torn down after, and the shared docker-compose
 *       Postgres is never touched. This module's {@code src/test/resources/application.properties}
 *       only disables Flyway, so the H2 URL has to be supplied here;</li>
 *   <li>excludes {@code KafkaAutoConfiguration}, removing the producer/consumer factories so no
 *       connection to {@code localhost:9092} is ever attempted;</li>
 *   <li>sets {@code kyc.vendor.webhook.secret} explicitly. {@code KycWebhookFilter} declares a dev
 *       fallback for it, so pinning it here changes nothing functionally — it states the value the
 *       context boots with instead of leaving it implicit in a filter's {@code @Value} default.</li>
 * </ul>
 *
 * <p>{@code @SpringBootTest} rather than a slice annotation is the point: a slice would load a
 * subset of beans and prove nothing about whether the application as a whole starts.
 *
 * <p>No fixtures, no {@code @BeforeEach}, no shared state.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:profiletestdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
    "kyc.vendor.webhook.secret=SuperSecretVendorKey123!"
})
class ProfileServiceApplicationTests {

    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void applicationContext_kafkaAutoConfigurationExcludedAndH2Datasource_startsWithAllBeansWired() {
    }
}
