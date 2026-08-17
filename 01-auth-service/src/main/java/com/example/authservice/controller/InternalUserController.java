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

/**
 * Serves the parts of the user row that other services own no copy of: the username and the
 * phone number.
 *
 * <p>auth-service is the only service that stores a username at all, and transaction-service
 * needs one to answer "who am I about to send money to?" before a transfer is confirmed.
 * profile-service used to keep its own copy of the phone number and let the KYC form edit it,
 * which meant the form could be updated while 2FA codes kept going to the value stored here,
 * with nothing to show the two had diverged. It now reads and writes through this class instead,
 * leaving {@code users.phone_number} the single source of truth for where codes are delivered.
 *
 * <p>Every mapping here is spelled out in full under {@code /api/v1/internal/} on purpose. That
 * prefix is not routed by the ingress and is gated by {@code InternalTokenFilter}'s shared
 * secret; a new endpoint on this class mapped outside the prefix would be published to the
 * internet with neither protection.
 */
@RestController
public class InternalUserController {

    private final UserRepository userRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;

    public InternalUserController(UserRepository userRepository,
                                  PhoneNumberNormalizer phoneNumberNormalizer) {
        this.userRepository = userRepository;
        this.phoneNumberNormalizer = phoneNumberNormalizer;
    }

    /**
     * Carries the name to show for a user, and nothing else off the user row.
     *
     * @param userId the resolved user, echoed back so a caller batching lookups can match
     *     responses to requests
     * @param displayName currently the username; never an email address or a phone number
     */
    public record DisplayNameResponse(Long userId, String displayName) {}

    /**
     * Carries the stored phone number in E.164, or {@code null} when the account has none.
     *
     * @param phoneNumber {@code null} is a legitimate value, not an error
     */
    public record PhoneNumberResponse(String phoneNumber) {}

    /**
     * Resolves a user id to the name to show a sender confirming a transfer.
     *
     * <p>Returns only the username. A caller confirming a payee should learn who they are paying
     * and nothing more, so the phone number and email on the same row are withheld even though
     * this endpoint is only reachable from inside the cluster.
     *
     * @param userId need not exist; an unknown id is a {@code 404} rather than an empty body
     * @return {@code 200} with the display name, or {@code 404} when no such user exists
     */
    @GetMapping("/api/v1/internal/users/{userId}/display-name")
    public ResponseEntity<DisplayNameResponse> getDisplayName(@PathVariable Long userId) {
        return userRepository.findById(userId)
                .map(user -> ResponseEntity.ok(new DisplayNameResponse(user.getId(), user.getUsername())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Reads the phone number 2FA codes for this account are delivered to.
     *
     * <p>"No number on file" and "no such user" are answered differently on purpose, because the
     * caller has to distinguish them: the column is nullable for accounts that predate it, so a
     * missing number is a {@code 200} carrying {@code null} while a missing user is a
     * {@code 404}.
     *
     * @param userId need not exist; an unknown id is a {@code 404}
     * @return {@code 200} with the stored E.164 number or with {@code null}, or {@code 404} when
     *     no such user exists
     */
    @GetMapping("/api/v1/internal/users/{userId}/phone-number")
    public ResponseEntity<?> getPhoneNumber(@PathVariable Long userId) {
        return userRepository.findById(userId)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(new PhoneNumberResponse(user.getPhoneNumber())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Replaces the phone number 2FA codes are delivered to, after normalizing and de-duplicating it.
     *
     * <p>This is the write that makes the endpoint worth protecting: whoever can call it decides
     * where a given account's 2FA codes land. The submitted value is normalized with the same
     * component and rejected with the same message the signup form uses, so a number typed into
     * the KYC form and the same number typed at registration are stored identically; the SMS
     * provider silently refuses anything that is not E.164.
     *
     * <p>Re-submitting the number the user already holds is a success, not a conflict. The
     * identity form posts every field on every save, so a plain existence check would reject
     * edits to unrelated fields; only a number belonging to a different account conflicts.
     *
     * @param userId must reference an existing user or the call is a {@code 404}
     * @param request must carry {@code phoneNumber} in any format the normalizer accepts, for
     *     example {@code 571-285-6947} or {@code +15712856947}
     * @return {@code 200} echoing the stored E.164 form rather than the submitted string, so the
     *     caller can show the user where codes will actually go; {@code 400} when the number
     *     cannot be normalized; {@code 409} when it already belongs to another account
     */
    @PutMapping("/api/v1/internal/users/{userId}/phone-number")
    public ResponseEntity<?> updatePhoneNumber(@PathVariable Long userId,
                                               @RequestBody Map<String, String> request) {
        Optional<User> existingUser = userRepository.findById(userId);
        if (existingUser.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        User user = existingUser.get();

        String normalizedPhone = phoneNumberNormalizer.normalize(request.get("phoneNumber")).orElse(null);
        if (normalizedPhone == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Please enter a valid phone number, e.g. 571-285-6947 or +15712856947"));
        }

        Optional<User> currentHolder = userRepository.findByPhoneNumber(normalizedPhone);
        if (currentHolder.isPresent() && !currentHolder.get().getId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "That phone number is already registered"));
        }

        user.setPhoneNumber(normalizedPhone);
        userRepository.save(user);

        return ResponseEntity.ok(new PhoneNumberResponse(normalizedPhone));
    }
}
