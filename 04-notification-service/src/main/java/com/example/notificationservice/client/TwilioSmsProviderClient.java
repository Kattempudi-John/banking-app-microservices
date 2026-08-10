package com.example.notificationservice.client;

import com.twilio.Twilio;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Real SMS delivery via Twilio Programmable Messaging - active when sms.provider=twilio, mutually
// exclusive with the logging and Textbelt clients so Spring only ever has one candidate bean.
// Credentials come from config (see application.yml), never from source.
@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "twilio")
public class TwilioSmsProviderClient implements SmsProviderClient {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsProviderClient.class);

    private final String accountSid;
    private final String authToken;
    private final String fromNumber;

    public TwilioSmsProviderClient(@Value("${sms.twilio.account-sid:}") String accountSid,
                                   @Value("${sms.twilio.auth-token:}") String authToken,
                                   @Value("${sms.twilio.from-number:}") String fromNumber) {
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromNumber = fromNumber;
    }

    // Deliberately fails startup rather than at send time. A missing credential discovered while
    // dispatching a 2FA code would surface as a login that silently never completes; discovered at
    // boot it's an obvious, immediate error naming exactly which property is blank.
    @PostConstruct
    void initialiseClient() {
        requireConfigured(accountSid, "sms.twilio.account-sid");
        requireConfigured(authToken, "sms.twilio.auth-token");
        requireConfigured(fromNumber, "sms.twilio.from-number");

        // Twilio's client keeps credentials in static state, so this is initialised once here rather
        // than per send.
        Twilio.init(accountSid, authToken);
        log.info("Twilio SMS provider initialised, sending from {}", fromNumber);
    }

    private void requireConfigured(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "sms.provider=twilio requires " + propertyName + " to be set. Set it in application.yml "
                            + "or via its environment variable, or switch sms.provider back to 'logging'.");
        }
    }

    @Override
    public void send(String phoneNumber, String message) {
        try {
            Message sent = Message.creator(new PhoneNumber(phoneNumber), new PhoneNumber(fromNumber), message).create();

            log.info("SUCCESS: SMS dispatched via Twilio. To: [{}], sid: {}, status: {}",
                    phoneNumber, sent.getSid(), sent.getStatus());
        } catch (ApiException e) {
            // Rethrown as a RuntimeException on purpose: NotificationProviderService retries on that
            // type and records a FAILED NotificationRecord once the retries are exhausted, the same
            // way a Textbelt failure is handled.
            throw new RuntimeException("Twilio SMS send failed: " + e.getMessage(), e);
        }
    }
}
