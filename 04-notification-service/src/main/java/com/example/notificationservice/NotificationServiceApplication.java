package com.example.notificationservice;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

// four separate enable annotations stacked here, learned each one turns on a different piece
// of spring's opt in behavior, feign clients, the @cacheable annotations on ProfileServiceClient,
// and the @retryable/@recover pair in NotificationProviderService, none of them work without these
//
// @EnableScheduling is the one that was missing. DailyBalanceSummaryJob has carried a @Scheduled
// cron expression since it was written, but the annotation alone does nothing - without scheduling
// turned on somewhere in the context, spring never creates the background thread that fires it, so
// the hourly sweep silently never ran in a real process. The test suite missed it because it calls
// processDailySummaries() directly rather than waiting for a trigger.
@SpringBootApplication
@EnableFeignClients
@EnableCaching
@EnableRetry
@EnableScheduling
public class NotificationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(NotificationServiceApplication.class, args);
	}

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

}
