package com.example.authservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;

/**
 * Context-load smoke test for the auth service: it asserts nothing about behaviour, only that every
 * bean in the application can be wired together at all.
 *
 * <p><strong>Slice:</strong> {@code @SpringBootTest} with no slice narrowing and no
 * {@code @AutoConfigureMockMvc}. A narrower slice would defeat the point — the whole value of this
 * test is that it loads the <em>complete</em> context, so a bean added to any package with a
 * missing or ambiguous dependency fails here rather than at deployment. There is no
 * {@code MockMvc} because no request is ever made.
 *
 * <p><strong>What is real versus mocked:</strong> everything is real except the four beans below.
 * The controllers, services, repositories, JPA mappings and security filter chain are all
 * instantiated exactly as in production; that is what makes a startup failure meaningful.
 *
 * <p><strong>Why the mocking exists.</strong> Each {@code @MockBean} replaces a bean that would
 * otherwise reach outside the JVM or drag in infrastructure that has nothing to do with whether the
 * wiring is sound:
 * <ul>
 *   <li>{@link KafkaTemplate} — {@code AuthSecurityService} injects it, but
 *       {@code KafkaAutoConfiguration} is excluded by the property block below, so nothing would
 *       define it. The mock is what supplies the bean, and it also guarantees that no producer ever
 *       tries to reach a broker that is not running in CI.</li>
 *   <li>{@link AuthenticationProvider} — the real one is built in {@code ApplicationConfig} from
 *       {@code CustomUserDetailsService} and the password encoder, and would authenticate against
 *       the users table. Mocking it keeps context startup off the database's user data.</li>
 *   <li>{@link AuthenticationManager} — derived from Spring Security's
 *       {@code AuthenticationConfiguration} and injected by {@code AuthController}; mocked for the
 *       same reason as the provider, and so the two stay consistent.</li>
 *   <li>{@link UserDetailsService} — injected by {@code JwtAuthenticationFilter}. The mock stands in
 *       for {@code CustomUserDetailsService}, which loads users through {@code UserRepository}.</li>
 * </ul>
 * Because the point is only that the context assembles, none of these mocks is stubbed — a bare
 * Mockito mock satisfies the injection point, which is all that is needed.
 *
 * <p><strong>Why {@code @TestPropertySource}:</strong> it swaps the configured Postgres datasource
 * for an in-memory H2 database and turns on {@code ddl-auto=create-drop} so Hibernate builds the
 * schema from the entity mappings at startup — this test must be runnable with no container
 * available. {@code DB_CLOSE_DELAY=-1} keeps the H2 database alive between connections for the life
 * of the JVM, so the schema created at startup is still there when a later connection arrives.
 * Flyway is switched off separately in {@code src/test/resources/application.properties}, so the
 * production migrations are not replayed against H2. The Kafka autoconfiguration exclusion stops
 * Spring attempting to build producer and consumer factories against a broker address that will not
 * answer.
 *
 * <p><strong>Fixture state and transactions:</strong> there is none of either. No
 * {@code @BeforeEach}, no {@code @Transactional}, and nothing is written to the database, so there
 * is no rollback behaviour to speak of. The context itself is cached and shared by the Spring
 * TestContext framework; nothing here dirties it, so no {@code @DirtiesContext} is needed.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
})
class AuthServiceApplicationTests {

    @MockBean
    private AuthenticationManager authenticationManager;

    @MockBean
    private AuthenticationProvider authenticationProvider;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Deliberately empty. The assertion is the startup itself: if any bean fails to resolve, Spring
     * throws while building the context and the test fails before this body is ever entered, so an
     * explicit assertion here would have nothing left to check.
     */
    @Test
    void applicationContext_securityAndKafkaBeansMocked_startsWithoutFailingToWire() {
    }
}
