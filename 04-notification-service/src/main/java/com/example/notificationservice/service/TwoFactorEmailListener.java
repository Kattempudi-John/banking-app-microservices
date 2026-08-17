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

// Consumes the TWO_FA_REQUESTED event auth-service publishes on every login that needs a 2FA code.
// Delivery moved from SMS to email, which is why this class is no longer TwoFactorSmsListener: the
// code now goes out through the same SendGrid/Twilio email path the alerts and summaries already use,
// so 2FA stops being the one channel needing its own paid provider and its own phone number on file.
//
// The event still carries phoneNumber - auth-service publishes the whole contact set - and this
// listener deliberately ignores it. Reading it would only invite a future "fall back to SMS" branch
// that dispatches a live credential over a second channel nobody asked for.
@Service
public class TwoFactorEmailListener {

    private static final Logger logger = LoggerFactory.getLogger(TwoFactorEmailListener.class);

    private static final String EMAIL_SUBJECT = "Your verification code";

    // The TTL auth-service currently issues, duplicated here only as a fallback. The event is the
    // source of truth; this exists so an event that predates the expiresInSeconds key (or arrives
    // from a producer that has not been redeployed yet) still produces a sentence rather than an
    // exception. Keep it equal to application.security.two-factor.code-ttl-seconds' default.
    private static final long DEFAULT_TTL_SECONDS = 180L;

    private final NotificationProviderService notificationProviderService;
    private final NotificationRecordRepository notificationRecordRepository;

    public TwoFactorEmailListener(NotificationProviderService notificationProviderService,
                                  NotificationRecordRepository notificationRecordRepository) {
        this.notificationProviderService = notificationProviderService;
        this.notificationRecordRepository = notificationRecordRepository;
    }

    @KafkaListener(topics = "notification-events", groupId = "notification-service-group")
    public void consumeTwoFactorRequest(Map<String, Object> event) {
        try {
            if (!"TWO_FA_REQUESTED".equals(event.get("action"))) {
                return;
            }

            Long userId = Long.valueOf(event.get("userId").toString());
            String email = (String) event.get("email");
            String code = (String) event.get("code");

            // The login screen counts this exact TTL down next to the code entry box, so the sentence
            // in the email has to come from the same number rather than a literal. Hardcoding it is
            // what put "It expires in 5 minutes." next to a timer that ran out at 3:00 - the user
            // reads the email, believes they have two minutes left, and gets EXPIRED instead.
            long ttlSeconds = readTtlSeconds(event);

            // Two different strings on purpose. The first is the real email and has to carry the code;
            // the second is what gets written to notification_records, and must not.
            String htmlBody = buildHtmlBody(code, ttlSeconds);

            // A login that needs a code and an account with no address to send it to is a dead end, so
            // record the miss and stop rather than handing the provider a blank recipient - the same
            // way TransactionAlertListener and DailyBalanceSummaryJob treat a missing address. Silence
            // here would leave the user staring at a code entry box with nothing explaining why.
            if (email == null || email.isBlank()) {
                logger.warn("User {} has no email address on file - the 2FA code could not be delivered", userId);
                persistRecord(userId, "Verification code could not be sent - no email address on file.",
                        NotificationStatus.FAILED);
                return;
            }

            // dispatchEmail's @Recover swallows the exception after exhausting retries (same return
            // type as the retried method), so its boolean return - not a caught exception - is
            // what actually tells us whether this ultimately succeeded.
            boolean dispatched = notificationProviderService.dispatchEmail(email, EMAIL_SUBJECT, htmlBody);
            NotificationStatus status = dispatched ? NotificationStatus.SENT : NotificationStatus.FAILED;

            if (dispatched) {
                logger.info("Notification Service: 2FA code emailed to {}", maskEmail(email));
            }

            // The record used to store the message body verbatim, which put a live login credential
            // into GET /api/v1/notifications - a page the user (or anyone who got hold of their
            // session) could reopen long afterwards and read the code straight off (see V3). The codes
            // expire in minutes, so this was a narrow window rather than a standing key, but a
            // one-time code is exactly the kind of thing an audit trail should record the sending of,
            // not the content. Moving to email changes nothing about that - only which identifier gets
            // masked in the line that is stored instead.
            persistRecord(userId, "Verification code sent to %s.".formatted(maskEmail(email)), status);
        } catch (Exception e) {
            logger.error("Failed to process 2FA email event", e);
        }
    }

    // Text block rather than concatenation, matching TransactionAlertListener and
    // DailyBalanceSummaryJob - the markup reads as markup and no quote in it needs escaping.
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

    // The TTL arrives as a String in a Map<String,Object> whose values this service does not control,
    // so every failure mode is a fallback rather than a throw: auth-service and notification-service
    // deploy independently, and a listener that blew up on a missing key would drop every in-flight
    // code the moment one of the two shipped first. Number is handled alongside String because a JSON
    // deserializer is free to hand back an Integer for an unquoted value, and "180".toString() and
    // 180.toString() have to land in the same place.
    private long readTtlSeconds(Map<String, Object> event) {
        Object raw = event.get("expiresInSeconds");
        if (raw == null) {
            // Not an error worth a warn: this is exactly what an old producer looks like.
            return DEFAULT_TTL_SECONDS;
        }
        if (raw instanceof Number number) {
            long value = number.longValue();
            return value > 0 ? value : DEFAULT_TTL_SECONDS;
        }
        try {
            long parsed = Long.parseLong(raw.toString().trim());
            // Zero or negative would render "It expires in 0 minutes." - a sentence that is worse
            // than the default, since the code being mailed is demonstrably still live.
            return parsed > 0 ? parsed : DEFAULT_TTL_SECONDS;
        } catch (NumberFormatException e) {
            logger.warn("Unparseable expiresInSeconds '{}' on a 2FA event - falling back to {}s", raw,
                    DEFAULT_TTL_SECONDS);
            return DEFAULT_TTL_SECONDS;
        }
    }

    // Renders the TTL the way a person would say it. Whole minutes are the normal case (180 -> "3
    // minutes"), but the value comes from config and nothing stops it being 90 or 45, so the odd
    // shapes get a correct sentence too instead of an integer-divided "1 minute" that undersells how
    // long the user actually has. Singulars are spelled out because "It expires in 1 minutes." is the
    // kind of thing that makes a real email look like a phishing attempt.
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

    // "user@example.com" -> "u***@example.com", the email counterpart of the "***4567" the phone
    // number used to get: enough for the reader to recognise their own address, not enough for the
    // stored row to republish it. Defensive about odd or missing input because this feeds a record
    // written inside a Kafka listener - throwing here would abandon the audit row for a send that
    // already happened.
    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "the email address on file";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            // No local part to keep - either no "@" at all or an address that starts with one. There
            // is nothing safe to show, and inventing a shape for a malformed address helps nobody.
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
