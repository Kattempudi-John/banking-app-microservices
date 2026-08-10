package com.example.notificationservice.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// default SMS provider so local dev (and CI) never needs a real SMS account or any credentials -
// active unless sms.provider names a real one, mirroring LoggingEmailProviderClient.
// matchIfMissing is what makes "no configuration at all" land here rather than failing to start.
@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "logging", matchIfMissing = true)
public class LoggingSmsProviderClient implements SmsProviderClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsProviderClient.class);

    @Override
    public void send(String phoneNumber, String message) {
        log.info("SUCCESS: SMS payload delivered to external provider. To: [{}], Body: {}", phoneNumber, message);
    }
}
