package com.example.notificationservice;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Boots the notification service and turns on the opt-in Spring features the rest of the module
 * depends on.
 *
 * <p>Each enable annotation here is load-bearing rather than decorative: {@code @EnableFeignClients}
 * activates the profile, account and auth clients, {@code @EnableCaching} activates the
 * {@code @Cacheable} preference lookups, and {@code @EnableRetry} activates the
 * {@code @Retryable}/{@code @Recover} pair in {@code NotificationProviderService} — without it a
 * provider failure throws on the first attempt instead of being retried.
 *
 * <p>{@code @EnableScheduling} fixes a live bug rather than enabling a new feature.
 * {@code DailyBalanceSummaryJob} has carried a {@code @Scheduled} cron expression since it was
 * written, but with scheduling off nowhere in the context creates the background thread, so the
 * hourly sweep never fired in a real process. Tests did not catch it because they call
 * {@code processDailySummaries()} directly rather than waiting for a trigger. Removing this
 * annotation silently stops all daily summaries again.
 */
@SpringBootApplication
@EnableFeignClients
@EnableCaching
@EnableRetry
@EnableScheduling
public class NotificationServiceApplication {

	/**
	 * Starts the Spring application context.
	 *
	 * @param args standard Spring Boot arguments; may be empty but never {@code null}
	 */
	public static void main(String[] args) {
		SpringApplication.run(NotificationServiceApplication.class, args);
	}

	/**
	 * Supplies the UTC clock every time-dependent component reads instead of calling
	 * {@code Instant.now()} directly.
	 *
	 * <p>It exists so {@code DailyBalanceSummaryJob} can be tested at a frozen instant: the job
	 * decides whether a user is due by comparing the current instant against their chosen hour in
	 * their own zone, which is untestable against the real system clock. Overriding this bean in a
	 * test context is the supported way to move time.
	 *
	 * @return a UTC clock; the zone matters because callers resolve local hours through a stored
	 *     zone id rather than the JVM default
	 */
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

}
