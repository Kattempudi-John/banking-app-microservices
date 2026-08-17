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

/**
 * Delivers email through Twilio's own Email API, {@code POST https://comms.twilio.com/v1/Emails}.
 *
 * <p>Selected when {@code email.provider=twilio}, which deselects
 * {@link LoggingEmailProviderClient} and {@link SendGridEmailProviderClient} so Spring only ever has
 * one {@link EmailProviderClient} candidate. This carries every notification the service sends, 2FA
 * codes included since those moved off SMS.
 *
 * <p>This is not the classic SendGrid v3 {@code mail/send} endpoint that
 * {@link SendGridEmailProviderClient} targets. The difference that matters operationally: it
 * authenticates with the same {@code sms.twilio.account-sid} / {@code sms.twilio.auth-token} pair
 * the SMS client already uses, so turning email on adds no new secret — only a verified sender
 * address under {@code email.twilio.from-email}. There is no Twilio Java helper for this endpoint,
 * so the call goes over a plain {@link RestTemplate}.
 *
 * <p>Selecting this provider makes the application refuse to start unless those three properties
 * are set; see {@link #initialiseClient()}.
 */
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

    /**
     * Captures the Twilio credentials and sender identity, and builds the default
     * {@link RestTemplate}.
     *
     * <p>The account credentials are read from {@code sms.twilio.*} rather than a duplicate pair
     * under {@code email.twilio.*} on purpose: it is one Twilio account with one SID and token, and
     * a second copy in config is a second place to rotate and one of the two to forget. Only the
     * sender identity is email-specific.
     *
     * <p>All four properties default to the empty string so a missing value is reported by name in
     * {@link #initialiseClient()} rather than failing as an unresolved placeholder.
     *
     * @param accountSid the {@code sms.twilio.account-sid} value, shared with
     *     {@link TwilioSmsProviderClient}; doubles as the HTTP Basic username
     * @param authToken the {@code sms.twilio.auth-token} value, shared with the SMS client
     * @param fromEmail the {@code email.twilio.from-email} value; must be verified as a sender on
     *     the Twilio account or every send is rejected
     * @param fromName optional display name; blank or {@code null} sends the address alone
     */
    public TwilioEmailProviderClient(@Value("${sms.twilio.account-sid:}") String accountSid,
                                     @Value("${sms.twilio.auth-token:}") String authToken,
                                     @Value("${email.twilio.from-email:}") String fromEmail,
                                     @Value("${email.twilio.from-name:}") String fromName) {
        this(accountSid, authToken, fromEmail, fromName, new RestTemplate());
    }

    TwilioEmailProviderClient(String accountSid, String authToken, String fromEmail, String fromName,
                              RestTemplate restTemplate) {
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
        this.restTemplate = restTemplate;
    }

    /**
     * Verifies the shared Twilio credentials and the sender address are present.
     *
     * <p>Runs during context refresh, so a blank property brings the application down at boot with a
     * message naming it, rather than surfacing inside a Kafka listener as a notification that is
     * silently never delivered. {@code email.twilio.from-name} is optional and is not checked.
     *
     * @throws IllegalStateException when {@code sms.twilio.account-sid},
     *     {@code sms.twilio.auth-token} or {@code email.twilio.from-email} is missing or blank
     */
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

    /**
     * Posts the message to Twilio Email over HTTP Basic auth and treats any non-2xx as a failure.
     *
     * <p>A success is 202 Accepted: Twilio queues the message and sends it asynchronously, so a
     * normal return means accepted-for-delivery, not delivered. The {@code operationId} from the
     * response is logged because it is the only handle for looking a send up in the Twilio console
     * afterwards — without it a "the email never arrived" report cannot be traced.
     *
     * <p>Only an HTML body is sent; the plain-text alternative is deliberately omitted rather than
     * generated, so there is no stripped-down duplicate to drift out of sync.
     *
     * @param userEmail the recipient address, passed through unvalidated for Twilio to reject
     * @param subject plain text; markup here is rejected by the provider
     * @param htmlContent an HTML document, JSON-escaped by Jackson before transport
     * @throws RuntimeException when Twilio answers outside 2xx or the call fails in transport;
     *     unchecked on purpose so {@code NotificationProviderService} retries it and, once the
     *     attempts are spent, records a {@code FAILED} notification
     */
    @Override
    public void send(String userEmail, String subject, String htmlContent) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(accountSid, authToken);

        SendEmailRequest body = new SendEmailRequest(
                new Address(fromEmail, fromName),
                List.of(new Address(userEmail, null)),
                new Content(subject, htmlContent, null));

        try {
            ResponseEntity<SendEmailResponse> response =
                    restTemplate.postForEntity(SEND_ENDPOINT, new HttpEntity<>(body, headers), SendEmailResponse.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Twilio Email rejected the message with status " + response.getStatusCode());
            }

            SendEmailResponse accepted = response.getBody();
            log.info("SUCCESS: Email dispatched via Twilio. To: [{}], Subject: {}, status: {}, operationId: {}",
                    userEmail, subject, response.getStatusCode().value(),
                    accepted != null ? accepted.operationId() : "unknown");
        } catch (RestClientException e) {
            throw new RuntimeException("Twilio Email send failed: " + e.getMessage(), e);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SendEmailRequest(Address from, List<Address> to, Content content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Address(String address, String name) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Content(String subject, String html, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SendEmailResponse(String operationId, String operationLocation) {}
}
