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

/**
 * Delivers SMS through Twilio Programmable Messaging.
 *
 * <p>Selected when {@code sms.provider=twilio}, which deselects {@link LoggingSmsProviderClient} and
 * {@link TextBeltSmsProviderClient} so Spring only ever has one {@link SmsProviderClient} candidate.
 * This is the option intended for real volume; Textbelt is a one-message-a-day demo path.
 *
 * <p>Selecting this provider makes the application refuse to start unless
 * {@code sms.twilio.account-sid}, {@code sms.twilio.auth-token} and
 * {@code sms.twilio.from-number} are all set; see {@link #initialiseClient()}. Credentials come
 * from configuration only, never from source. The SID and token are shared with
 * {@link TwilioEmailProviderClient}, so rotating them affects both channels.
 */
@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "twilio")
public class TwilioSmsProviderClient implements SmsProviderClient {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsProviderClient.class);

    private final String accountSid;
    private final String authToken;
    private final String fromNumber;

    /**
     * Captures the Twilio credentials without initialising the SDK.
     *
     * <p>All three properties default to the empty string so a missing value is reported by name in
     * {@link #initialiseClient()} rather than failing as an unresolved placeholder.
     *
     * @param accountSid the {@code sms.twilio.account-sid} value, shared with
     *     {@link TwilioEmailProviderClient}
     * @param authToken the {@code sms.twilio.auth-token} value, shared with the email client
     * @param fromNumber the {@code sms.twilio.from-number} value in E.164 form; must be a number
     *     purchased on or verified for the account
     */
    public TwilioSmsProviderClient(@Value("${sms.twilio.account-sid:}") String accountSid,
                                   @Value("${sms.twilio.auth-token:}") String authToken,
                                   @Value("${sms.twilio.from-number:}") String fromNumber) {
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromNumber = fromNumber;
    }

    /**
     * Verifies the credentials are present and initialises the Twilio SDK.
     *
     * <p>Fails startup rather than send time on purpose: a missing credential discovered while
     * dispatching a 2FA code surfaces as a login that silently never completes, whereas at boot it
     * is an immediate error naming exactly which property is blank.
     *
     * <p>{@code Twilio.init} is called once here rather than per send because the SDK holds the
     * credentials in static state.
     *
     * @throws IllegalStateException when {@code sms.twilio.account-sid},
     *     {@code sms.twilio.auth-token} or {@code sms.twilio.from-number} is missing or blank
     */
    @PostConstruct
    void initialiseClient() {
        requireConfigured(accountSid, "sms.twilio.account-sid");
        requireConfigured(authToken, "sms.twilio.auth-token");
        requireConfigured(fromNumber, "sms.twilio.from-number");

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

    /**
     * Hands the message to Twilio and logs the returned message SID and queue status.
     *
     * <p>Returning normally means Twilio accepted and queued the message, not that a handset
     * received it; the logged SID is the handle for looking the real outcome up in the console.
     *
     * @param phoneNumber E.164 form including the country code, passed through unaltered for Twilio
     *     to reject if malformed
     * @param message plain text; a body over one segment is billed and delivered as several
     * @throws RuntimeException wrapping the Twilio {@code ApiException}; unchecked on purpose so
     *     {@code NotificationProviderService} retries it and, once the attempts are spent, records a
     *     {@code FAILED} notification, exactly as a Textbelt failure is handled
     */
    @Override
    public void send(String phoneNumber, String message) {
        try {
            Message sent = Message.creator(new PhoneNumber(phoneNumber), new PhoneNumber(fromNumber), message).create();

            log.info("SUCCESS: SMS dispatched via Twilio. To: [{}], sid: {}, status: {}",
                    phoneNumber, sent.getSid(), sent.getStatus());
        } catch (ApiException e) {
            throw new RuntimeException("Twilio SMS send failed: " + e.getMessage(), e);
        }
    }
}
