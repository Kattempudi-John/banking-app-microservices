package com.example.notificationservice.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// default email provider so local dev and CI need no SendGrid account or key - active unless
// email.provider names a real one. matchIfMissing is what makes "no configuration at all" land here.
// Mirrors LoggingSmsProviderClient exactly.
@Component
@ConditionalOnProperty(name = "email.provider", havingValue = "logging", matchIfMissing = true)
public class LoggingEmailProviderClient implements EmailProviderClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailProviderClient.class);

    @Override
    public void send(String userEmail, String subject, String htmlContent) {
        log.info("SUCCESS: Email payload delivered to external provider. To: [{}], Subject: {}", userEmail, subject);
    }
}
