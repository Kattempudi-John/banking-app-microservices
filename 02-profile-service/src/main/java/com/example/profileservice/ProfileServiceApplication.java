package com.example.profileservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

// @EnableFeignClients has to go on this main class (or somewhere spring scans) or
// AuthServiceClient never actually gets wired up as a real bean anywhere
@SpringBootApplication
@EnableFeignClients
public class ProfileServiceApplication {

	// same pattern as every other service's main method, boots the embedded server and the
	// whole spring context, this class is what maven builds into the runnable jar's manifest
	public static void main(String[] args) {
		SpringApplication.run(ProfileServiceApplication.class, args);
	}

}
