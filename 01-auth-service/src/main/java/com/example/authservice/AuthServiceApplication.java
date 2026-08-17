package com.example.authservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Boots the auth service and enables the scheduler its background jobs depend on.
 *
 * <p>Scheduling is switched on here and nowhere else, so removing {@code @EnableScheduling} from
 * this class silently disables every {@code @Scheduled} method in the service rather than failing
 * at startup. {@code AuthSecurityService.purgeExpiredBlacklistTokens} is the one that matters: with
 * the scheduler off, the revoked-JWT blacklist table is never trimmed and grows without bound.
 */
@SpringBootApplication
@EnableScheduling
public class AuthServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuthServiceApplication.class, args);
	}

}
