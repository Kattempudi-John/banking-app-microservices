package com.example.transactionservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Boots the transaction service.
 *
 * <p>{@code @EnableFeignClients} is declared here rather than on a nested configuration class
 * because the Feign interfaces are only turned into beans when the annotation sits somewhere
 * component scanning reaches. Moving it off this class leaves {@code ProfileServiceClient} and
 * its siblings unwired at runtime instead of failing at compile time.
 */
@SpringBootApplication
@EnableFeignClients
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

}
