package com.example.notificationservice.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

// Real email delivery via Twilio Email - active when email.provider=twilio, mutually exclusive with
// the logging and SendGrid clients so Spring only ever has one candidate bean. Carries every
// notification this service sends, 2FA codes included since they moved off SMS.
//
// This is Twilio's own email API (POST https://comms.twilio.com/v1/Emails), NOT the classic SendGrid
// v3 mail/send that SendGridEmailProviderClient targets. The practical difference that matters here:
// it authenticates with the same account SID / auth token pair the SMS client already uses, so
// switching email on needs no new credential - only a verified sender address. There is no Twilio
// Java helper for this endpoint yet, so it goes over a plain RestTemplate the way
// TextBeltSmsProviderClient does.
@Component
@ConditionalOnProperty(name = "email.provider", havingValue = "twilio")
public class TwilioEmailProviderClient implements EmailProviderClient {

    private static final Logger log = LoggerFactory.getLogger(TwilioEmailProviderClient.class);
    private static final String SEND_ENDPOINT = "https://comms.twilio.com/v1/Emails";

    private final String accountSid;
    private final String authToken;
    private final String fromEmail;
    private final String fromName;
    private final RestTemplate restTemplate;

    // The credentials deliberately read from sms.twilio.* rather than a duplicate pair under
    // email.twilio.*: it is one Twilio account with one SID/token, and two copies of the same secret
    // in config is two places to rotate and one of them to forget.
    public TwilioEmailProviderClient(@Value("${sms.twilio.account-sid:}") String accountSid,
                                     @Value("${sms.twilio.auth-token:}") String authToken,
                                     @Value("${email.twilio.from-email:}") String fromEmail,
                                     @Value("${email.twilio.from-name:}") String fromName) {
        this(accountSid, authToken, fromEmail, fromName, new RestTemplate());
    }

    // Package-private so the test suite can hand in a RestTemplate that MockRestServiceServer is
    // bound to - TextBeltSmsProviderClient builds its own inline and is untestable without a network.
    TwilioEmailProviderClient(String accountSid, String authToken, String fromEmail, String fromName,
                              RestTemplate restTemplate) {
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
        this.restTemplate = restTemplate;
    }

    // Same reasoning as TwilioSmsProviderClient and SendGridEmailProviderClient: fail at boot with a
    // message naming the missing property, rather than discovering it inside a Kafka listener where
    // it becomes a silently undelivered alert.
    @PostConstruct
    void initialiseClient() {
        requireConfigured(accountSid, "sms.twilio.account-sid");
        requireConfigured(authToken, "sms.twilio.auth-token");
        requireConfigured(fromEmail, "email.twilio.from-email");

        log.info("Twilio email provider initialised, sending from {}", fromEmail);
    }

    private void requireConfigured(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "email.provider=twilio requires " + propertyName + " to be set. Set it in application.yml "
                            + "or via its environment variable, or switch email.provider back to 'logging'.");
        }
    }

    @Override
    public void send(String userEmail, String subject, String htmlContent) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Twilio Email uses plain HTTP Basic with the account SID as the username, exactly like the
        // rest of the Twilio REST API.
        headers.setBasicAuth(accountSid, authToken);

        // Every caller builds an HTML body (see the text blocks in TransactionAlertListener and
        // DailyBalanceSummaryJob), so the text alternative is left null rather than shipping a
        // stripped-down duplicate that would drift from the HTML.
        SendEmailRequest body = new SendEmailRequest(
                new Address(fromEmail, fromName),
                List.of(new Address(userEmail, null)),
                new Content(subject, htmlContent, null));

        try {
            ResponseEntity<SendEmailResponse> response =
                    restTemplate.postForEntity(SEND_ENDPOINT, new HttpEntity<>(body, headers), SendEmailResponse.class);

            // Twilio Email answers 202 Accepted and then processes the send asynchronously, so a 2xx
            // means accepted-for-delivery rather than delivered. The operationId is the handle for
            // looking the outcome up later in the console; logging it is what makes a "the email
            // never arrived" report traceable.
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Twilio Email rejected the message with status " + response.getStatusCode());
            }

            SendEmailResponse accepted = response.getBody();
            log.info("SUCCESS: Email dispatched via Twilio. To: [{}], Subject: {}, status: {}, operationId: {}",
                    userEmail, subject, response.getStatusCode().value(),
                    accepted != null ? accepted.operationId() : "unknown");
        } catch (RestClientException e) {
            // Rethrown unchecked so NotificationProviderService's @Retryable retries it and its
            // @Recover records a FAILED NotificationRecord once the attempts are exhausted. Non-2xx
            // responses land here too - RestTemplate's default error handler throws on them.
            throw new RuntimeException("Twilio Email send failed: " + e.getMessage(), e);
        }
    }

    // Request/response shapes for POST /v1/Emails. Records rather than hand-built JSON strings so
    // Jackson handles escaping - the HTML bodies are full documents full of quotes and angle brackets.
    //
    // NON_NULL matters here: the optional fields below (a recipient display name, the plain-text
    // alternative) are left null, and Twilio's API is stricter about an explicit "text": null than
    // about the key being absent. Package-private rather than private so Jackson can reach the
    // canonical constructor when deserialising the response without relying on setAccessible.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SendEmailRequest(Address from, List<Address> to, Content content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Address(String address, String name) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Content(String subject, String html, String text) {}

    // Twilio's 202 body: {"operationId": "comms_operation_...", "operationLocation": "https://..."}
    // Polling operationLocation for the final delivery outcome is deliberately not done here.
    // Ignoring unknown fields so a future addition to Twilio's response body can't break a send that
    // actually succeeded.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SendEmailResponse(String operationId, String operationLocation) {}
}
