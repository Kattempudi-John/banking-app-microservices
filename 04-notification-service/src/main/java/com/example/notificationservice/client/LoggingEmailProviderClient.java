package com.example.notificationservice.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Pretends to deliver email by writing the payload to the log instead of contacting a provider.
 *
 * <p>Selected when {@code email.provider=logging}, and — because of {@code matchIfMissing = true} —
 * also whenever the property is absent altogether. That default is the zero-configuration
 * guarantee: local dev and CI start and exercise the full notification path with no SendGrid or
 * Twilio account, no API key, and no outbound network. Naming a real provider deselects this bean,
 * so exactly one {@link EmailProviderClient} is ever in the context.
 *
 * <p>Mirrors {@link LoggingSmsProviderClient}. Never configure it in an environment that is
 * expected to actually reach users: every send silently succeeds, including 2FA codes, so a
 * production deployment left on this default logs codes and delivers nothing.
 */
@Component
@ConditionalOnProperty(name = "email.provider", havingValue = "logging", matchIfMissing = true)
public class LoggingEmailProviderClient implements EmailProviderClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailProviderClient.class);

    @Override
    public void send(String userEmail, String subject, String htmlContent) {
        log.info("SUCCESS: Email payload delivered to external provider. To: [{}], Subject: {}", userEmail, subject);
        log.info("DEV ONLY - Email body: {}", htmlContent);
    }
}
