package com.example.notificationservice.client;

/**
 * Sends outbound email through whichever provider the deployment has configured.
 *
 * <p>This is a plain interface, not a {@code @FeignClient} like {@link ProfileServiceClient},
 * {@link AccountServiceClient} and {@link AuthServiceClient} in the same package: those are
 * generated HTTP calls to sibling services, whereas this one is implemented by hand-written classes
 * that talk to a third-party provider SDK or REST API.
 *
 * <p>Exactly one implementation is ever a bean. Each is selected by {@code @ConditionalOnProperty}
 * on {@code email.provider}: {@code sendgrid} selects {@link SendGridEmailProviderClient},
 * {@code twilio} selects {@link TwilioEmailProviderClient}, and anything else — including the
 * property being absent entirely — selects {@link LoggingEmailProviderClient}, which is what lets
 * the service boot with no email credentials at all.
 */
public interface EmailProviderClient {

    /**
     * Dispatches one email, blocking until the provider has accepted or rejected it.
     *
     * <p>Delivery is best-effort past acceptance: providers acknowledge and then send
     * asynchronously, so a normal return means accepted-for-delivery, not delivered. Failures are
     * signalled by an unchecked exception, which is what
     * {@code NotificationProviderService}'s retry and recovery path keys on.
     *
     * @param userEmail a deliverable address; implementations do not validate it, the provider does
     * @param subject plain text, no HTML — providers reject markup here
     * @param htmlContent an HTML document; every caller in this service builds HTML, so no plain
     *     text alternative is sent alongside it
     */
    void send(String userEmail, String subject, String htmlContent);
}
