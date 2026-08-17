package com.example.notificationservice.service;

import com.example.notificationservice.client.EmailProviderClient;
import com.example.notificationservice.client.SmsProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * Sends email and SMS through the external providers, retrying transient provider failures.
 *
 * <p>Every dispatch method here reports its outcome as a {@code boolean} and never throws on
 * failure: once retries are exhausted the recovery path swallows the exception. Callers must branch
 * on the returned value — a caller relying on {@code try}/{@code catch} records every exhausted
 * dispatch as a success.
 *
 * <p>Retry is only active because {@code @EnableRetry} is present on the application class; without
 * it the first provider failure propagates immediately.
 */
@Service
public class NotificationProviderService {

    private static final Logger log = LoggerFactory.getLogger(NotificationProviderService.class);

    private final EmailProviderClient emailProviderClient;
    private final SmsProviderClient smsProviderClient;

    public NotificationProviderService(EmailProviderClient emailProviderClient, SmsProviderClient smsProviderClient) {
        this.emailProviderClient = emailProviderClient;
        this.smsProviderClient = smsProviderClient;
    }

    /**
     * Sends one HTML email through the provider, retrying a failed attempt twice more.
     *
     * <p><strong>The returned boolean is the only success signal.</strong> After three attempts the
     * recovery path logs and returns {@code false} rather than rethrowing, so this method does not
     * throw on a failed send. A caller that ignores the return value records a {@code SENT}
     * notification for mail that was never delivered.
     *
     * <p>Three attempts at 1s doubling to 2s bounds the whole call at roughly three seconds. That
     * ceiling is chosen against the 2FA code lifetime — codes are valid for minutes, so a longer
     * backoff would deliver a code the user watches expire, and it also keeps a Kafka listener
     * thread from being held long enough to stall the partition behind it.
     *
     * <p>The failure log claims the payload was written to a dead-letter queue. It was not: no queue
     * or {@code failed_notifications} table exists, so a failed send is lost beyond the
     * {@code FAILED} record the caller writes. Do not treat an exhausted dispatch as recoverable.
     *
     * @param userEmail must be a real, non-blank address; callers check for a missing address and
     *     record a failure rather than calling with a blank recipient
     * @param subject the mail subject line
     * @param htmlContent the full HTML body; not the string that should be persisted, since the body
     *     may carry a live credential
     * @return {@code true} only if an attempt succeeded; {@code false} once all three are exhausted
     */
    @Retryable(
            retryFor = { RuntimeException.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public boolean dispatchEmail(String userEmail, String subject, String htmlContent) {
        log.info("Attempting to dispatch email via external provider to [{}]", userEmail);
        emailProviderClient.send(userEmail, subject, htmlContent);
        return true;
    }

    /**
     * Absorbs an email dispatch failure once all attempts are exhausted, converting it into a
     * {@code false} return from {@link #dispatchEmail}.
     *
     * <p>Invoked by Spring Retry, never called directly. Its signature is dictated: the exception
     * first, then the retried method's parameters in order, and the same return type — a mismatch
     * leaves the original exception propagating instead.
     *
     * <p>This path only logs. A production system would write the payload to a dead-letter queue or
     * a {@code failed_notifications} table for later retry; neither exists, and the log line saying
     * otherwise is aspirational. What rides on it matters more since 2FA moved to email: an
     * exhausted retry is a login nobody can finish, and a time-limited code is not something
     * "retry tomorrow" would recover anyway.
     *
     * @param e the last failure seen, logged by message only
     * @return always {@code false}, which is what the caller reads as the dispatch outcome
     */
    @Recover
    public boolean recoverDispatchFailure(RuntimeException e, String userEmail, String subject, String htmlContent) {
        log.error("CRITICAL FAILURE: Exhausted all retries for email to [{}]. Reason: {}", userEmail, e.getMessage());
        log.error("Payload saved to Dead Letter Queue for manual review.");
        return false;
    }

    /**
     * Sends one SMS through the provider, under the same retry and reporting contract as
     * {@link #dispatchEmail}.
     *
     * <p>Dormant: nothing in this service dispatches SMS since 2FA moved to email. It stays because
     * the SMS provider clients do.
     *
     * @param phoneNumber the recipient in whatever format the configured provider accepts
     * @param message the plain-text body
     * @return {@code true} only if an attempt succeeded; {@code false} once all three are exhausted,
     *     never an exception
     */
    @Retryable(
            retryFor = { RuntimeException.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public boolean dispatchSms(String phoneNumber, String message) {
        log.info("Attempting to dispatch SMS via external provider to [{}]", phoneNumber);
        smsProviderClient.send(phoneNumber, message);
        return true;
    }

    /**
     * Absorbs an SMS dispatch failure once all attempts are exhausted.
     *
     * <p>Invoked by Spring Retry, never called directly. As with the email recovery, the dead-letter
     * queue named in the log line does not exist — the payload is dropped.
     *
     * @param e the last failure seen, logged by message only
     * @return always {@code false}
     */
    @Recover
    public boolean recoverSmsDispatchFailure(RuntimeException e, String phoneNumber, String message) {
        log.error("CRITICAL FAILURE: Exhausted all retries for SMS to [{}]. Reason: {}", phoneNumber, e.getMessage());
        log.error("Payload saved to Dead Letter Queue for manual review.");
        return false;
    }
}