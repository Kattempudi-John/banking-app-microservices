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

// Real email delivery via SendGrid (Twilio's email product, separate API and separate key from the
// SMS credentials) - active when email.provider=sendgrid, mutually exclusive with the logging client.
// Carries the balance summaries and transaction alerts; 2FA stays on SMS.
@Component
@ConditionalOnProperty(name = "email.provider", havingValue = "sendgrid")
public class SendGridEmailProviderClient implements EmailProviderClient {

    private static final Logger log = LoggerFactory.getLogger(SendGridEmailProviderClient.class);
    private static final String SEND_ENDPOINT = "mail/send";

    private final String apiKey;
    private final String fromEmail;
    private SendGrid sendGrid;

    public SendGridEmailProviderClient(@Value("${email.sendgrid.api-key:}") String apiKey,
                                       @Value("${email.sendgrid.from-email:}") String fromEmail) {
        this.apiKey = apiKey;
        this.fromEmail = fromEmail;
    }

    // Same reasoning as TwilioSmsProviderClient: fail at boot with a message naming the missing
    // property, rather than discovering it inside a Kafka listener where it becomes a silently
    // undelivered alert.
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

    @Override
    public void send(String userEmail, String subject, String htmlContent) {
        // Every caller builds an HTML body (see the text blocks in TransactionAlertListener and
        // DailyBalanceSummaryJob), so text/html is always the right content type here.
        Mail mail = new Mail(new Email(fromEmail), subject, new Email(userEmail), new Content("text/html", htmlContent));

        Request request = new Request();
        request.setMethod(Method.POST);
        request.setEndpoint(SEND_ENDPOINT);

        try {
            request.setBody(mail.build());
            Response response = sendGrid.api(request);

            // SendGrid answers 202 Accepted on success. Anything outside the 2xx range means the
            // message was rejected, and the body explains why - surface it rather than assuming sent.
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw new RuntimeException("SendGrid rejected the message with status "
                        + response.getStatusCode() + ": " + response.getBody());
            }

            log.info("SUCCESS: Email dispatched via SendGrid. To: [{}], Subject: {}, status: {}",
                    userEmail, subject, response.getStatusCode());
        } catch (IOException e) {
            // Rethrown unchecked so NotificationProviderService's @Retryable retries it and its
            // @Recover records a FAILED NotificationRecord once the attempts are exhausted.
            throw new RuntimeException("SendGrid email send failed: " + e.getMessage(), e);
        }
    }
}
