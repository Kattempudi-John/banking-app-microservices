package com.example.notificationservice.client;

import java.io.IOException;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Delivers email through the classic SendGrid v3 {@code mail/send} API.
 *
 * <p>Selected when {@code email.provider=sendgrid}, which deselects
 * {@link LoggingEmailProviderClient} and {@link TwilioEmailProviderClient} so Spring only ever has
 * one {@link EmailProviderClient} candidate. SendGrid is Twilio's email product but a separate API
 * with its own key: {@code email.sendgrid.api-key} is unrelated to the {@code sms.twilio.*}
 * credentials, unlike {@link TwilioEmailProviderClient}, which reuses them.
 *
 * <p>Selecting this provider makes the application refuse to start unless
 * {@code email.sendgrid.api-key} and {@code email.sendgrid.from-email} are both set; see
 * {@link #initialiseClient()}. That is deliberate — this client carries every notification the
 * service sends, 2FA codes included since those moved off SMS, so a misconfiguration here is a
 * login nobody can complete rather than an alert nobody reads, and it is better found at boot than
 * inside a Kafka listener.
 */
@Component
@ConditionalOnProperty(name = "email.provider", havingValue = "sendgrid")
public class SendGridEmailProviderClient implements EmailProviderClient {

    private static final Logger log = LoggerFactory.getLogger(SendGridEmailProviderClient.class);
    private static final String SEND_ENDPOINT = "mail/send";

    private final String apiKey;
    private final String fromEmail;
    private SendGrid sendGrid;

    /**
     * Captures the SendGrid credentials without contacting SendGrid.
     *
     * <p>Both properties carry an empty-string default so that a missing value reaches
     * {@link #initialiseClient()} and is reported by property name, rather than failing earlier as
     * an unresolvable placeholder that names nothing useful.
     *
     * @param apiKey the {@code email.sendgrid.api-key} value; blank here is tolerated only until
     *     {@code @PostConstruct} runs
     * @param fromEmail the {@code email.sendgrid.from-email} value; must be an address verified as a
     *     sender in the SendGrid account or every send is rejected at delivery time
     */
    public SendGridEmailProviderClient(@Value("${email.sendgrid.api-key:}") String apiKey,
                                       @Value("${email.sendgrid.from-email:}") String fromEmail) {
        this.apiKey = apiKey;
        this.fromEmail = fromEmail;
    }

    /**
     * Verifies the credentials are present and builds the SendGrid client.
     *
     * <p>Runs during context refresh, so a blank property brings the whole application down at boot
     * with a message naming it. The alternative — discovering it on the first send — turns a typo
     * into a notification that is silently never delivered.
     *
     * @throws IllegalStateException when {@code email.sendgrid.api-key} or
     *     {@code email.sendgrid.from-email} is missing or blank
     */
    @PostConstruct
    void initialiseClient() {
        requireConfigured(apiKey, "email.sendgrid.api-key");
        requireConfigured(fromEmail, "email.sendgrid.from-email");

        this.sendGrid = new SendGrid(apiKey);
        log.info("SendGrid email provider initialised, sending from {}", fromEmail);
    }

    private void requireConfigured(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "email.provider=sendgrid requires " + propertyName + " to be set. Set it in application.yml "
                            + "or via its environment variable, or switch email.provider back to 'logging'.");
        }
    }

    /**
     * Posts the message to SendGrid and treats any non-2xx response as a failure.
     *
     * <p>The status check is not redundant: the SendGrid SDK returns a {@link Response} for
     * rejections instead of throwing, so without it a 401 or a 400 would be logged as a successful
     * send. A success is 202 Accepted, meaning queued rather than delivered.
     *
     * <p>The body is always sent as {@code text/html} with no plain-text alternative, because every
     * caller in this service composes an HTML document.
     *
     * @param userEmail the recipient address, passed through unvalidated for SendGrid to reject
     * @param subject plain text; markup here is rejected by the provider
     * @param htmlContent an HTML document, sent verbatim as the only body part
     * @throws RuntimeException when SendGrid answers outside 2xx or the call fails in transport;
     *     unchecked on purpose so {@code NotificationProviderService} retries it and, once the
     *     attempts are spent, records a {@code FAILED} notification
     */
    @Override
    public void send(String userEmail, String subject, String htmlContent) {
        Mail mail = new Mail(new Email(fromEmail), subject, new Email(userEmail), new Content("text/html", htmlContent));

        Request request = new Request();
        request.setMethod(Method.POST);
        request.setEndpoint(SEND_ENDPOINT);

        try {
            request.setBody(mail.build());
            Response response = sendGrid.api(request);

            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw new RuntimeException("SendGrid rejected the message with status "
                        + response.getStatusCode() + ": " + response.getBody());
            }

            log.info("SUCCESS: Email dispatched via SendGrid. To: [{}], Subject: {}, status: {}",
                    userEmail, subject, response.getStatusCode());
        } catch (IOException e) {
            throw new RuntimeException("SendGrid email send failed: " + e.getMessage(), e);
        }
    }
}
