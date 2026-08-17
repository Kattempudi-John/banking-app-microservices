package com.example.notificationservice.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.notificationservice.model.NotificationChannel;
import com.example.notificationservice.model.NotificationRecord;
import com.example.notificationservice.model.NotificationStatus;
import com.example.notificationservice.model.NotificationType;
import com.example.notificationservice.repository.NotificationRecordRepository;

/**
 * Emails a login verification code in response to the {@code TWO_FA_REQUESTED} event auth-service
 * publishes.
 *
 * <p>Delivery moved from SMS to email, which is why this is no longer {@code TwoFactorSmsListener}:
 * the code goes out over the same email path the alerts and summaries use, so 2FA no longer needs
 * its own paid provider or a phone number on file. The event still carries {@code phoneNumber} and
 * this listener deliberately ignores it — reading it invites a "fall back to SMS" branch that would
 * dispatch a live credential over a second channel nobody asked for.
 *
 * <p>Two different strings are built per event on purpose: the code goes to the email provider,
 * while what is persisted is a masked line naming only the address. That keeps the verification code
 * out of {@code GET /api/v1/notifications}, a page the user — or anyone holding their session — can
 * reopen long afterwards. An audit trail should record that a one-time code was sent, not its
 * contents.
 */
@Service
public class TwoFactorEmailListener {

    private static final Logger logger = LoggerFactory.getLogger(TwoFactorEmailListener.class);

    private static final String EMAIL_SUBJECT = "Your verification code";

    private static final long DEFAULT_TTL_SECONDS = 180L;

    private final NotificationProviderService notificationProviderService;
    private final NotificationRecordRepository notificationRecordRepository;

    public TwoFactorEmailListener(NotificationProviderService notificationProviderService,
                                  NotificationRecordRepository notificationRecordRepository) {
        this.notificationProviderService = notificationProviderService;
        this.notificationRecordRepository = notificationRecordRepository;
    }

    /**
     * Consumes a {@code notification-events} message and emails the verification code it carries.
     *
     * <p>Ignores every message whose {@code action} is not {@code TWO_FA_REQUESTED}; the topic
     * carries other traffic.
     *
     * <p>Writes a {@code NotificationRecord} either way — {@code SENT}, or {@code FAILED} when the
     * user has no address on file or the provider gave up. Success is read from
     * {@code dispatchEmail}'s boolean return, not from the absence of an exception, because its
     * recovery path swallows the failure.
     *
     * <p>Never throws. A failure is logged and the message treated as consumed, since an exception
     * escaping a Kafka listener redelivers it forever and stalls every code queued behind it.
     *
     * @param event must carry a numeric {@code userId}, an {@code email} and a {@code code}; an
     *     absent or blank {@code email} is recorded as a failed send rather than passed to the
     *     provider
     */
    @KafkaListener(topics = "notification-events", groupId = "notification-service-group")
    public void consumeTwoFactorRequest(Map<String, Object> event) {
        try {
            if (!"TWO_FA_REQUESTED".equals(event.get("action"))) {
                return;
            }

            Long userId = Long.valueOf(event.get("userId").toString());
            String email = (String) event.get("email");
            String code = (String) event.get("code");

            long ttlSeconds = readTtlSeconds(event);

            String htmlBody = buildHtmlBody(code, ttlSeconds);

            if (email == null || email.isBlank()) {
                logger.warn("User {} has no email address on file - the 2FA code could not be delivered", userId);
                persistRecord(userId, "Verification code could not be sent - no email address on file.",
                        NotificationStatus.FAILED);
                return;
            }

            boolean dispatched = notificationProviderService.dispatchEmail(email, EMAIL_SUBJECT, htmlBody);
            NotificationStatus status = dispatched ? NotificationStatus.SENT : NotificationStatus.FAILED;

            if (dispatched) {
                logger.info("Notification Service: 2FA code emailed to {}", maskEmail(email));
            }

            persistRecord(userId, "Verification code sent to %s.".formatted(maskEmail(email)), status);
        } catch (Exception e) {
            logger.error("Failed to process 2FA email event", e);
        }
    }

    private String buildHtmlBody(String code, long ttlSeconds) {
        return """
               <html>
                   <body>
                       <h2>Your verification code</h2>
                       <p>Use this code to finish signing in:</p>
                       <div style="font-size: 28px; font-weight: bold; letter-spacing: 4px; color: #2E86C1;">
                           %s
                       </div>
                       <p>It expires in %s.</p>
                       <p>If you did not try to sign in, someone else may know your password - change it now.</p>
                   </body>
               </html>
               """.formatted(code, describeExpiry(ttlSeconds));
    }

    private long readTtlSeconds(Map<String, Object> event) {
        Object raw = event.get("expiresInSeconds");
        if (raw == null) {
            return DEFAULT_TTL_SECONDS;
        }
        if (raw instanceof Number number) {
            long value = number.longValue();
            return value > 0 ? value : DEFAULT_TTL_SECONDS;
        }
        try {
            long parsed = Long.parseLong(raw.toString().trim());
            return parsed > 0 ? parsed : DEFAULT_TTL_SECONDS;
        } catch (NumberFormatException e) {
            logger.warn("Unparseable expiresInSeconds '{}' on a 2FA event - falling back to {}s", raw,
                    DEFAULT_TTL_SECONDS);
            return DEFAULT_TTL_SECONDS;
        }
    }

    private String describeExpiry(long ttlSeconds) {
        long minutes = ttlSeconds / 60;
        long seconds = ttlSeconds % 60;

        if (minutes == 0) {
            return plural(seconds, "second");
        }
        if (seconds == 0) {
            return plural(minutes, "minute");
        }
        return plural(minutes, "minute") + " " + plural(seconds, "second");
    }

    private String plural(long value, String unit) {
        return value + " " + unit + (value == 1 ? "" : "s");
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "the email address on file";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private void persistRecord(Long userId, String message, NotificationStatus status) {
        NotificationRecord record = new NotificationRecord();
        record.setUserId(userId);
        record.setType(NotificationType.EMAIL_2FA);
        record.setChannel(NotificationChannel.EMAIL);
        record.setSubject(EMAIL_SUBJECT);
        record.setMessage(message);
        record.setStatus(status);
        notificationRecordRepository.save(record);
    }
}
