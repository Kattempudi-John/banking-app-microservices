package com.example.authservice.controller;

import com.example.authservice.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

// auth-service is the only service that knows a user's username - profile-service stores contact
// and KYC data but has no name field at all. transaction-service needs it to answer "who am I
// about to send money to?" before a transfer is confirmed, hence this internal lookup.
@RestController
public class InternalUserController {

    private final UserRepository userRepository;

    public InternalUserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public record DisplayNameResponse(Long userId, String displayName) {}

    // Deliberately returns only the username - never the phone number, email, or anything else on
    // the user row. A sender confirming a recipient should learn who they're paying and nothing more.
    @GetMapping("/api/v1/internal/users/{userId}/display-name")
    public ResponseEntity<DisplayNameResponse> getDisplayName(@PathVariable Long userId) {
        return userRepository.findById(userId)
                .map(user -> ResponseEntity.ok(new DisplayNameResponse(user.getId(), user.getUsername())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
