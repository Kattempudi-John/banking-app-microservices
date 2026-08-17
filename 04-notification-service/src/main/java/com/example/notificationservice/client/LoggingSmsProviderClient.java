package com.example.notificationservice.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Pretends to deliver SMS by writing the payload to the log instead of contacting a provider.
 *
 * <p>Selected when {@code sms.provider=logging}, and — because of {@code matchIfMissing = true} —
 * also whenever the property is absent altogether, so a service with no SMS credentials starts
 * cleanly rather than failing. Naming {@code twilio} or {@code textbelt} deselects this bean, so
 * exactly one {@link SmsProviderClient} is ever in the context.
 *
 * <p>Mirrors {@link LoggingEmailProviderClient}: every send succeeds and nothing leaves the
 * process, so this default must not be left in place anywhere real messages are expected.
 */
@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "logging", matchIfMissing = true)
public class LoggingSmsProviderClient implements SmsProviderClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsProviderClient.class);

    @Override
    public void send(String phoneNumber, String message) {
        log.info("SUCCESS: SMS payload delivered to external provider. To: [{}], Body: {}", phoneNumber, message);
    }
}
