package com.example.authservice.controller;


import com.example.authservice.model.User;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.service.AutomationOtpStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/test-support")
@ConditionalOnProperty(
        name = "automation.test-support.enabled",
        havingValue = "true"
)
public class AutomationTestController {

    private final UserRepository userRepository;
    private final AutomationOtpStore automationOtpStore;

    public AutomationTestController(
            UserRepository userRepository,
            AutomationOtpStore automationOtpStore
    ) {
        this.userRepository = userRepository;
        this.automationOtpStore = automationOtpStore;
    }

    @GetMapping("/otp")
    public ResponseEntity<Map<String, Object>> getOtp(
            @RequestParam String username
    ) {

        Optional<User> userOptional =
                userRepository.findByUsername(username);

        if (userOptional.isEmpty()) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(createResponse(
                            "OTP_NOT_FOUND",
                            "No OTP available for the requested user."
                    ));
        }

        User user = userOptional.get();

        String otp =
                automationOtpStore.get(user.getId());

        if (otp == null) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(createResponse(
                            "OTP_NOT_FOUND",
                            "No active OTP is available for the requested user."
                    ));
        }

        Map<String, Object> response = new HashMap<>();

        response.put("status", "OTP_AVAILABLE");
        response.put("username", username);
        response.put("otp", otp);

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> createResponse(
            String status,
            String message
    ) {

        Map<String, Object> response = new HashMap<>();

        response.put("status", status);
        response.put("message", message);

        return response;
    }
}
