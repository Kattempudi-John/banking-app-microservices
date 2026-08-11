package com.example.authservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @SpringBootApplication bundles three annotations into one, @Configuration, @EnableAutoConfiguration,
// and @ComponentScan, this single line is what turns on all of spring boot's autoconfiguration magic
//
// @EnableScheduling is what AuthSecurityService.purgeExpiredBlacklistTokens' comment was referring to
// when it said @Scheduled "just needs spring's scheduling support turned on somewhere" - it was never
// actually turned on, so that hourly purge had never run and the blacklist table only ever grew.
@SpringBootApplication
@EnableScheduling
public class AuthServiceApplication {

	// this is the actual java entry point, SpringApplication.run boots up the whole embedded
	// tomcat server and the entire application context before this method even returns
	public static void main(String[] args) {
		SpringApplication.run(AuthServiceApplication.class, args);
	}

}
