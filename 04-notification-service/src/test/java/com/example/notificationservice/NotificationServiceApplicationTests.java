package com.example.notificationservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class NotificationServiceApplicationTests {

	@Autowired
	private ApplicationContext applicationContext;

	// default spring boot smoke test, just confirms the notification service context wires up
	// this one intentionally uses the default kafka config pointing at an unreachable local broker,
	// which proves consumer containers just retry quietly in the background without blocking startup
	@Test
	void contextLoads() {
	}

	// Regression guard for a bug that shipped silently: DailyBalanceSummaryJob carried a @Scheduled
	// cron expression, but nothing in the app ever declared @EnableScheduling, so spring never created
	// the trigger and the hourly sweep simply never ran in a real process. Nothing caught it, because
	// every other test calls processDailySummaries() directly rather than waiting for a trigger.
	//
	// ScheduledAnnotationBeanPostProcessor is the bean @EnableScheduling registers and the thing that
	// actually turns a @Scheduled method into a running task - if it is absent, every cron job in this
	// service is dead code again.
	@Test
	@DisplayName("Scheduling is enabled, so @Scheduled jobs actually run - [MEANT TO PASS]")
	void schedulingIsEnabled() {
		assertThat(applicationContext.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class))
				.as("@EnableScheduling must stay on NotificationServiceApplication, or DailyBalanceSummaryJob never fires")
				.isNotEmpty();
	}

}
