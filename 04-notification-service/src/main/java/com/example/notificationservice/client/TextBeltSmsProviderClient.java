package com.example.notificationservice.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

// SMS delivery via Textbelt (https://textbelt.com) - active when sms.provider=textbelt, mutually
// exclusive with the other SmsProviderClient beans so Spring never sees two candidates. The free
// "textbelt" key needs no account/signup, just 1 text/day/IP, which makes it a decent zero-setup way
// to prove the 2FA path end-to-end. sms.provider=twilio is the real option for actual volume.
@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "textbelt")
public class TextBeltSmsProviderClient implements SmsProviderClient {

    private static final Logger log = LoggerFactory.getLogger(TextBeltSmsProviderClient.class);
    private static final String TEXTBELT_URL = "https://textbelt.com/text";

    private final RestTemplate restTemplate = new RestTemplate();
    private final String apiKey;

    public TextBeltSmsProviderClient(@Value("${sms.textbelt-key}") String apiKey) {
        this.apiKey = apiKey;
    }

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

    // Textbelt's response shape: {"success": true, "quotaRemaining": 40, "textId": 12345}
    // or {"success": false, "quotaRemaining": 0, "error": "Out of quota"}
    private record TextbeltResponse(boolean success, Integer quotaRemaining, String error, String textId) {
    }
}
