package com.example.accountservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Boots the account service, sole owner of the accounts and transactions ledger.
 *
 * <p>Feign client scanning is enabled here because it is what turns {@code ProfileServiceClient}
 * from a bare interface into an injectable bean; without it {@code KycEnforcementAspect} has no
 * way to ask profile-service for the caller's KYC status and every gated operation fails closed.
 */
@SpringBootApplication
@EnableFeignClients
public class AccountServiceApplication {

	/**
	 * Starts the Spring application context.
	 *
	 * @param args standard Spring Boot arguments; may be empty but never {@code null}
	 */
	public static void main(String[] args) {
		SpringApplication.run(AccountServiceApplication.class, args);
	}

}
