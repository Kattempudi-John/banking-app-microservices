package com.example.notificationservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup smoke test for {@link NotificationServiceApplication}: the whole context wires up, and the
 * one opt-in Spring feature that has already been lost once is still switched on.
 *
 * <h2>Test configuration</h2>
 * <p>{@code @SpringBootTest} with no slice narrowing and no {@code @MockBean} at all, which is the
 * point — the subject under test <em>is</em> the fully assembled context, so replacing any part of
 * it would defeat the test. Everything is real: Feign clients, caching, retry, scheduling, the JPA
 * layer and the Kafka consumer configuration.
 *
 * <p>Consequences of booting the real thing, both deliberate:
 * <ul>
 *   <li>This module has no {@code src/test/resources}, so the context loads the real dev
 *       configuration and needs the Docker Postgres running. Adding a test config to avoid that is
 *       explicitly out of scope.</li>
 *   <li>The default Kafka configuration points at a local broker that is not up during a test run.
 *       The context still starts, which is itself worth knowing: consumer containers retry quietly
 *       in the background instead of blocking or failing startup.</li>
 * </ul>
 *
 * <h2>Fixture state</h2>
 * <p>None. The only injected collaborator is the {@link ApplicationContext} itself; there is no
 * {@code @BeforeEach} or {@code @BeforeAll}, no shared mutable state and no transaction, so the two
 * tests are order-independent.
 */
@SpringBootTest
class NotificationServiceApplicationTests {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void applicationContext_defaultConfiguration_startsWithoutError() {
	}

	/**
	 * Regression guard for a bug that shipped silently rather than a check of Spring's own wiring.
	 * {@code DailyBalanceSummaryJob} carried a {@code @Scheduled} cron expression, but nothing in the
	 * application ever declared {@code @EnableScheduling}, so Spring never created the trigger and the
	 * sweep simply never ran in a real process. No test caught it, because every other test calls
	 * {@code processDailySummaries()} directly instead of waiting for a trigger to fire.
	 *
	 * <p>{@link ScheduledAnnotationBeanPostProcessor} is the bean {@code @EnableScheduling} registers
	 * and the thing that actually turns a {@code @Scheduled} method into a running task. Asserting it
	 * is present is the closest a test can get to "the cron job is alive" without waiting on the
	 * clock — if it is ever absent again, every scheduled job in this service is dead code.
	 */
	@Test
	@DisplayName("Scheduling is enabled, so @Scheduled jobs actually run - [MEANT TO PASS]")
	void applicationContext_afterStartup_registersSchedulingPostProcessorSoDailySummaryJobFires() {
		assertThat(applicationContext.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class))
				.as("@EnableScheduling must stay on NotificationServiceApplication, or DailyBalanceSummaryJob never fires")
				.isNotEmpty();
	}

}
