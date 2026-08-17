package com.example.notificationservice;

import com.example.notificationservice.client.EmailProviderClient;
import com.example.notificationservice.client.SmsProviderClient;
import com.example.notificationservice.service.NotificationProviderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Covers the retry contract of {@link NotificationProviderService} — how many times a provider is
 * actually called, and what a caller sees when every one of those calls fails.
 *
 * <h2>What these four tests are really protecting</h2>
 * <p>{@code dispatchEmail}/{@code dispatchSms} are annotated {@code @Retryable(maxAttempts = 3)} and
 * paired with {@code @Recover} methods that <strong>return {@code false} rather than rethrowing</strong>.
 * The consequence is the whole point of this suite: once the attempts are spent, no exception ever
 * reaches the caller, so the {@code boolean} return value is the only success signal a caller gets.
 * Code that wraps a dispatch in {@code try}/{@code catch} and treats "no exception" as success will
 * record a {@code SENT} notification for mail that was never delivered. These tests pin both halves
 * of that — the attempt count, and the silence.
 *
 * <h2>Test configuration</h2>
 * <p>{@code @SpringBootTest} rather than a narrower slice or a plain Mockito unit test, because the
 * behaviour under test is not in the method bodies: it is in the Spring Retry AOP proxy that
 * {@code @EnableRetry} on {@code NotificationServiceApplication} wraps around the bean. A
 * hand-constructed {@code new NotificationProviderService(...)} would call the provider exactly once
 * and never invoke {@code @Recover}, so it would pass while proving nothing. The autowired bean has
 * to be the proxied one from a real context. No {@code @AutoConfigureMockMvc} — nothing here goes
 * through HTTP.
 *
 * <p>The two {@code @MockBean}s replace the outbound provider clients: {@link EmailProviderClient}
 * stands in for whichever email provider {@code email.provider} selects (Twilio, SendGrid or the
 * logging stub) and {@link SmsProviderClient} for the SMS equivalent. They are what makes failure
 * injectable — no real HTTP is issued and no live provider credentials are needed. Everything else
 * in the context, {@link NotificationProviderService} included, is the real bean.
 *
 * <p>This module has no {@code src/test/resources}, so the context boots the real dev configuration
 * and needs the Docker Postgres running. That is deliberate and shared across the module's
 * {@code @SpringBootTest} suites; this class touches no repository and no transaction, so there is
 * no rollback behaviour to reason about.
 *
 * <h2>Fixture state</h2>
 * <p>No {@code @BeforeEach} or {@code @BeforeAll}. Each test stubs its own mock inline and JUnit's
 * Mockito integration resets the {@code @MockBean}s between tests, so the {@code times(1)} and
 * {@code times(3)} counts start from zero every time and the tests are order-independent.
 */
@SpringBootTest
class NotificationProviderServiceTestSuite {

    @Autowired
    private NotificationProviderService notificationProviderService;

    @MockBean
    private EmailProviderClient emailProviderClient;

    @MockBean
    private SmsProviderClient smsProviderClient;

    @Test
    @DisplayName("Successful dispatch calls the provider exactly once, no retries - [MEANT TO PASS]")
    void dispatchEmail_providerSucceedsOnFirstAttempt_callsProviderExactlyOnce() {
        notificationProviderService.dispatchEmail("user_1@bank.com", "Subject", "<p>Body</p>");

        // times(1) is the assertion, not a formality: it proves the retry interceptor does not fire
        // on a clean send, which a plain verify() would let through silently.
        verify(emailProviderClient, times(1)).send("user_1@bank.com", "Subject", "<p>Body</p>");
    }

    /**
     * The exhausted-retry path for email. Note the runtime cost: the backoff is 1s doubling to 2s,
     * so this test genuinely waits about three seconds before {@code @Recover} takes over.
     */
    @Test
    @DisplayName("Provider failing every attempt is retried 3 times then recovers without propagating - [MEANT TO PASS]")
    void dispatchEmail_providerFailsEveryAttempt_retriesThreeTimesThenRecoversWithoutThrowing() {
        doThrow(new RuntimeException("503 Service Unavailable: SendGrid API Gateway timeout"))
                .when(emailProviderClient).send(anyString(), anyString(), anyString());

        // @Recover returns false instead of rethrowing, so a permanently dead provider must reach the
        // caller as a quiet false — never as an exception.
        assertThatCode(() ->
                notificationProviderService.dispatchEmail("user_2@bank.com", "Subject", "<p>Body</p>"))
                .doesNotThrowAnyException();

        // maxAttempts = 3 counts the first call plus two retries, not three retries on top of it.
        verify(emailProviderClient, times(3)).send(eq("user_2@bank.com"), eq("Subject"), eq("<p>Body</p>"));
    }

    @Test
    @DisplayName("SMS: Successful dispatch calls the provider exactly once, no retries - [MEANT TO PASS]")
    void dispatchSms_providerSucceedsOnFirstAttempt_callsProviderExactlyOnce() {
        notificationProviderService.dispatchSms("+15551234567", "Your verification code is 123456.");

        verify(smsProviderClient, times(1)).send("+15551234567", "Your verification code is 123456.");
    }

    /**
     * The SMS mirror of the email exhaustion case. It is not redundant: {@code dispatchSms} carries
     * its own {@code @Retryable} and its own {@code @Recover} overload, and a signature mismatch on
     * either would leave the original exception propagating out of a Kafka listener rather than
     * being absorbed.
     */
    @Test
    @DisplayName("SMS: Provider failing every attempt is retried 3 times then recovers without propagating - [MEANT TO PASS]")
    void dispatchSms_providerFailsEveryAttempt_retriesThreeTimesThenRecoversWithoutThrowing() {
        doThrow(new RuntimeException("503 Service Unavailable: Twilio API Gateway timeout"))
                .when(smsProviderClient).send(anyString(), anyString());

        assertThatCode(() ->
                notificationProviderService.dispatchSms("+15559876543", "Your verification code is 654321."))
                .doesNotThrowAnyException();

        verify(smsProviderClient, times(3)).send(eq("+15559876543"), eq("Your verification code is 654321."));
    }
}
