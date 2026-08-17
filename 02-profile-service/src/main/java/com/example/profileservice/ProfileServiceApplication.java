package com.example.profileservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Boots the profile service and its component scan.
 *
 * <p>Feign client scanning is enabled from this class because it is the root of the scanned package
 * tree; moved anywhere Spring does not scan, {@code AuthServiceClient} is never wired as a bean and
 * the context fails to start on the missing dependency.
 */
@SpringBootApplication
@EnableFeignClients
public class ProfileServiceApplication {

	/**
	 * Starts the Spring context and the embedded server.
	 *
	 * @param args forwarded to Spring Boot verbatim, so {@code --spring.profiles.active} and other
	 *     property overrides given on the command line take effect
	 */
	public static void main(String[] args) {
		SpringApplication.run(ProfileServiceApplication.class, args);
	}

}
