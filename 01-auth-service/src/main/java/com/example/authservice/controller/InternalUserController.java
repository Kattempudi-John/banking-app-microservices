package com.example.authservice.controller;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.authservice.model.User;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.util.PhoneNumberNormalizer;

// auth-service is the only service that knows a user's username - profile-service stores contact
// and KYC data but has no name field at all. transaction-service needs it to answer "who am I
// about to send money to?" before a transfer is confirmed, hence this internal lookup.
//
// Everything here sits under /api/v1/internal/ on purpose: that prefix is not routed by the k8s
// ingress, so these are reachable only from inside the cluster and have no end-user token to
// authenticate against (SecurityConfig permits the whole prefix for that reason). Adding an
// endpoint here outside that prefix would publish it to the internet unauthenticated.
@RestController
public class InternalUserController {

    private final UserRepository userRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;

    public InternalUserController(UserRepository userRepository,
                                  PhoneNumberNormalizer phoneNumberNormalizer) {
        this.userRepository = userRepository;
        this.phoneNumberNormalizer = phoneNumberNormalizer;
    }

    public record DisplayNameResponse(Long userId, String displayName) {}

    public record PhoneNumberResponse(String phoneNumber) {}

    // Deliberately returns only the username - never the phone number, email, or anything else on
    // the user row. A sender confirming a recipient should learn who they're paying and nothing more.
    @GetMapping("/api/v1/internal/users/{userId}/display-name")
    public ResponseEntity<DisplayNameResponse> getDisplayName(@PathVariable Long userId) {
        return userRepository.findById(userId)
                .map(user -> ResponseEntity.ok(new DisplayNameResponse(user.getId(), user.getUsername())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ==========================================
    // Phone number (owned here, read and written by profile-service)
    // ==========================================

    // profile-service used to keep its own copy of the number and let the KYC identity form edit it.
    // Two copies meant the KYC form could be updated while 2FA codes kept going to the value stored
    // here, with nothing to indicate the two had diverged - and profile-service had no uniqueness
    // check, so two profiles could claim one phone. It now reads and writes through these two
    // endpoints instead, leaving users.phone_number the single source of truth for the destination
    // codes are actually sent to.

    // Null body value is a legitimate answer, not an error: the column is nullable for accounts that
    // predate it. The caller needs to tell "no number on file" apart from "no such user", so the
    // missing user is the 404 and the missing number is a 200 carrying null.
    @GetMapping("/api/v1/internal/users/{userId}/phone-number")
    public ResponseEntity<?> getPhoneNumber(@PathVariable Long userId) {
        return userRepository.findById(userId)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(new PhoneNumberResponse(user.getPhoneNumber())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/api/v1/internal/users/{userId}/phone-number")
    public ResponseEntity<?> updatePhoneNumber(@PathVariable Long userId,
                                               @RequestBody Map<String, String> request) {
        Optional<User> existingUser = userRepository.findById(userId);
        if (existingUser.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        User user = existingUser.get();

        // Normalized with the same component registration uses, and rejected with the same message,
        // so a number typed into the KYC form and the same number typed into the signup form are
        // stored identically. Reimplementing the rules here would let the two drift apart, and the
        // half that got it wrong would produce a number the SMS provider silently refuses.
        String normalizedPhone = phoneNumberNormalizer.normalize(request.get("phoneNumber")).orElse(null);
        if (normalizedPhone == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Please enter a valid phone number, e.g. 571-285-6947 or +15712856947"));
        }

        // Looked up by owner rather than with existsByPhoneNumber, because the user's own number
        // has to come back through this as a success. The identity form re-submits every field on
        // every save, so an existence check alone would reject any edit to an unrelated field - the
        // conflict is only real when the number belongs to somebody else.
        Optional<User> currentHolder = userRepository.findByPhoneNumber(normalizedPhone);
        if (currentHolder.isPresent() && !currentHolder.get().getId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "That phone number is already registered"));
        }

        user.setPhoneNumber(normalizedPhone);
        userRepository.save(user);

        // Echoes the stored form back rather than what was sent, so the caller can show the user the
        // value 2FA codes will actually be delivered to instead of the string they typed.
        return ResponseEntity.ok(new PhoneNumberResponse(normalizedPhone));
    }
}
