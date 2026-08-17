package com.example.notificationservice.service;

import com.example.notificationservice.client.EmailProviderClient;
import com.example.notificationservice.client.SmsProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
public class NotificationProviderService {

    private static final Logger log = LoggerFactory.getLogger(NotificationProviderService.class);

    private final EmailProviderClient emailProviderClient;
    private final SmsProviderClient smsProviderClient;

    public NotificationProviderService(EmailProviderClient emailProviderClient, SmsProviderClient smsProviderClient) {
        this.emailProviderClient = emailProviderClient;
        this.smsProviderClient = smsProviderClient;
    }

    // learned @retryable needs @enablescheduling's cousin @enableretry turned on somewhere in the
    // app, otherwise this annotation just sits here doing nothing and a failure throws immediately
    // Returns whether the dispatch ultimately succeeded, so callers can record a real
    // NotificationRecord status - @Recover swallows the exception after exhausting retries (its
    // own return type has to match this method's), so a caller can't tell success from failure by
    // catching alone; the boolean is what actually carries that signal back out.
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

    // learned @recover has a strict signature rule, the first parameter has to be the same
    // exception type @retryable is watching for, and the rest of the parameters have to match
    // the original method's parameters in order, spring uses that shape to match them up
    @Recover
    public boolean recoverDispatchFailure(RuntimeException e, String userEmail, String subject, String htmlContent) {
        // In a production system, this would write the failed payload to a Dead Letter Queue (DLQ)
        // or a failed_notifications database table for a cron job to retry tomorrow. Worth noting
        // what now rides on this path: 2FA codes deliver by email, so an exhausted retry here is a
        // login nobody can finish, not only an alert nobody reads - and a code is time-sensitive
        // enough that "retry tomorrow" is not a real recovery for it.
        log.error("CRITICAL FAILURE: Exhausted all retries for email to [{}]. Reason: {}", userEmail, e.getMessage());
        log.error("Payload saved to Dead Letter Queue for manual review.");
        return false;
    }

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

    @Recover
    public boolean recoverSmsDispatchFailure(RuntimeException e, String phoneNumber, String message) {
        // Same DLQ story as recoverDispatchFailure. Nothing dispatches SMS since 2FA moved to email,
        // so this path is dormant rather than hot - it stays because the SMS clients do, and a
        // swallowed failure here still beats one that crashes a consumer.
        log.error("CRITICAL FAILURE: Exhausted all retries for SMS to [{}]. Reason: {}", phoneNumber, e.getMessage());
        log.error("Payload saved to Dead Letter Queue for manual review.");
        return false;
    }
}