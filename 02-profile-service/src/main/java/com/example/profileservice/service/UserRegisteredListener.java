package com.example.profileservice.service;

import com.example.profileservice.model.UserProfile;
import com.example.profileservice.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Provisions a local profile row when auth-service announces a newly registered user.
 *
 * <p>auth-service owns credentials only, so this listener is how profile-service learns a user
 * exists at all. The row it creates carries {@code id = userId}, the same convention
 * {@code UserProfile} uses everywhere else, and starts at {@code PENDING_VERIFICATION}.
 *
 * <p>A user whose event is never consumed therefore has no profile at all;
 * {@code ProfileController} compensates by provisioning the same row on read.
 */
@Service
public class UserRegisteredListener {

    private static final Logger logger = LoggerFactory.getLogger(UserRegisteredListener.class);

    private final UserProfileRepository userProfileRepository;

    public UserRegisteredListener(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    /**
     * Creates the profile row for a {@code user-events} message, ignoring users already provisioned.
     *
     * <p>Idempotent by design: a retry or redelivery for a user who already has a profile is skipped
     * rather than allowed to clobber whatever data has accumulated on that row since.
     *
     * <p>Every failure is logged and swallowed, so the offset is committed and the message is not
     * redelivered. There is no dead-letter queue, which means a malformed or unprocessable event is
     * lost and that user is left with no profile until something else provisions one. The commit is
     * deliberate — an unparseable message would otherwise be redelivered forever and stall the
     * partition — but it is the reason a missing-profile fallback has to exist elsewhere.
     *
     * @param event must carry a {@code userId} parsable as a {@code Long}; {@code phoneNumber} and
     *     {@code email} are optional and stored as-is, un-normalized, so they may not be in E.164
     */
    @KafkaListener(topics = "user-events", groupId = "profile-service-group")
    @Transactional
    public void consumeUserRegistered(Map<String, Object> event) {
        try {
            Long userId = Long.valueOf(event.get("userId").toString());

            if (userProfileRepository.existsById(userId)) {
                logger.info("Profile already exists for user id {}, skipping provisioning", userId);
                return;
            }

            UserProfile profile = new UserProfile();
            profile.setId(userId);
            profile.setPhoneNumber((String) event.get("phoneNumber"));
            profile.setEmail((String) event.get("email"));
            userProfileRepository.save(profile);

            logger.info("Provisioned profile for newly registered user id {}", userId);
        } catch (Exception e) {
            logger.error("Failed to process UserRegistered event", e);
        }
    }
}
