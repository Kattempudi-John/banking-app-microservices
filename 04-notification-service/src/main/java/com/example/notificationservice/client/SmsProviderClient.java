package com.example.notificationservice.client;

/**
 * Sends outbound SMS through whichever provider the deployment has configured.
 *
 * <p>Mirrors {@link EmailProviderClient}: a plain interface with hand-written implementations, not a
 * {@code @FeignClient} like the three sibling-service clients in this package.
 *
 * <p>Exactly one implementation is ever a bean. Each is selected by {@code @ConditionalOnProperty}
 * on {@code sms.provider}: {@code twilio} selects {@link TwilioSmsProviderClient},
 * {@code textbelt} selects {@link TextBeltSmsProviderClient}, and anything else — including the
 * property being absent entirely — selects {@link LoggingSmsProviderClient}, which is what lets the
 * service boot with no SMS credentials at all.
 */
public interface SmsProviderClient {

    /**
     * Dispatches one text message, blocking until the provider has accepted or rejected it.
     *
     * <p>Failures are signalled by an unchecked exception so that
     * {@code NotificationProviderService}'s retry and recovery path can act on them.
     *
     * @param phoneNumber E.164 form including the country code; implementations pass it through
     *     unaltered and the provider rejects anything else
     * @param message plain text only, no markup; long bodies are split into multiple billed
     *     segments by the provider rather than truncated
     */
    void send(String phoneNumber, String message);
}
