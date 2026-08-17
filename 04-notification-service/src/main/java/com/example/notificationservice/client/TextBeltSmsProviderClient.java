package com.example.notificationservice.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Delivers SMS through Textbelt, {@code https://textbelt.com}.
 *
 * <p>Selected when {@code sms.provider=textbelt}, which deselects {@link LoggingSmsProviderClient}
 * and {@link TwilioSmsProviderClient} so Spring only ever has one {@link SmsProviderClient}
 * candidate.
 *
 * <p>Intended as a demonstration path, not a production one: the free key literally spelled
 * {@code textbelt} needs no account or signup but allows one message per day per source IP, which
 * is enough to prove the 2FA delivery path against a real handset and nothing more. Use
 * {@code sms.provider=twilio} for any real volume.
 *
 * <p>Unlike the Twilio clients, the key is not validated at startup — a wrong or exhausted key
 * surfaces as a failed send.
 */
@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "textbelt")
public class TextBeltSmsProviderClient implements SmsProviderClient {

    private static final Logger log = LoggerFactory.getLogger(TextBeltSmsProviderClient.class);
    private static final String TEXTBELT_URL = "https://textbelt.com/text";

    private final RestTemplate restTemplate = new RestTemplate();
    private final String apiKey;

    /**
     * Captures the Textbelt key.
     *
     * @param apiKey the {@code sms.textbelt-key} value; it has no default, so selecting this
     *     provider without setting the property fails the context at startup on the unresolved
     *     placeholder
     */
    public TextBeltSmsProviderClient(@Value("${sms.textbelt-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Posts the message to Textbelt as a form submission and fails unless the body reports success.
     *
     * <p>The status check cannot be delegated to the HTTP code: Textbelt answers 200 even for a
     * refusal and reports the outcome in a {@code success} field, so a quota exhaustion looks like a
     * successful response until the body is read.
     *
     * @param phoneNumber E.164 form including the country code
     * @param message plain text, sent verbatim
     * @throws RuntimeException when Textbelt reports {@code success: false} — most often
     *     {@code Out of quota} on the free key — or answers with no body at all; unchecked on
     *     purpose so {@code NotificationProviderService} retries and then records a {@code FAILED}
     *     notification
     */
    @Override
    public void send(String phoneNumber, String message) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("phone", phoneNumber);
        body.add("message", message);
        body.add("key", apiKey);

        TextbeltResponse response = restTemplate.postForObject(TEXTBELT_URL, body, TextbeltResponse.class);

        if (response == null || !response.success()) {
            String error = response != null ? response.error() : "no response from Textbelt";
            throw new RuntimeException("Textbelt SMS send failed: " + error);
        }

        log.info("SUCCESS: SMS dispatched via Textbelt. To: [{}], quotaRemaining: {}", phoneNumber, response.quotaRemaining());
    }

    private record TextbeltResponse(boolean success, Integer quotaRemaining, String error, String textId) {
    }
}
