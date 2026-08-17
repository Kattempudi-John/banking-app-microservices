package com.example.notificationservice.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Covers {@link TwilioEmailProviderClient} — the HTTP wire contract it holds with Twilio's Email API
 * ({@code POST https://comms.twilio.com/v1/Emails}) and the startup guard in
 * {@link TwilioEmailProviderClient#initialiseClient()}.
 *
 * <h2>Slice and test configuration</h2>
 * <p>Plain JUnit 5. There is deliberately <strong>no Spring context and no database</strong> here,
 * and that is a decision rather than an omission: {@code 04-notification-service} has no
 * {@code src/test/resources}, so a {@code @SpringBootTest} boots the real dev configuration and
 * needs the Docker Postgres — {@code NotificationRecord} binds real PostgreSQL enum types that H2
 * cannot emulate. Requiring a live database to exercise an HTTP client that never touches one buys
 * nothing. {@link MockRestServiceServer} pins the entire request/response contract without either.
 *
 * <h2>What is real and what is faked</h2>
 * <p>The client itself is real, as is its Jackson serialisation and its
 * {@link org.springframework.http.HttpHeaders#setBasicAuth} header construction — those are exactly
 * what these tests exist to pin down. Only the transport is faked: {@link MockRestServiceServer}
 * binds to the {@link RestTemplate} handed to the client and answers from the expectations each test
 * declares, so no HTTP leaves the JVM. Nothing is a Mockito mock; there are no {@code @MockBean}s
 * because there is no context to put them in.
 *
 * <h2>Why this class can be tested at all, and {@code TextBeltSmsProviderClient} cannot</h2>
 * <p>{@link TwilioEmailProviderClient} carries a second, package-private constructor whose only
 * reason to exist is this suite: it takes the {@link RestTemplate} as a parameter, giving
 * {@link MockRestServiceServer} something to bind to. The public constructor calls
 * {@code new RestTemplate()} internally, which would leave no seam.
 * {@link TextBeltSmsProviderClient} has only that second form — it builds its {@link RestTemplate}
 * in a field initialiser with no injection point — so its send path cannot be intercepted this way
 * and has no equivalent suite.
 *
 * <p>The same seam argument dictates the package: this class sits in
 * {@code com.example.notificationservice.client} rather than beside the other suites one level up
 * because both the test constructor and {@code initialiseClient()} are package-private, and neither
 * is worth widening to {@code public} purely to relocate a test file.
 *
 * <h2>Fixture state</h2>
 * <p>All state is per-test. {@code @BeforeEach} builds a fresh {@link RestTemplate}, binds a fresh
 * {@link MockRestServiceServer} to it and constructs a fully-configured client from the shared SID /
 * token / sender constants, so one test's unmet expectation cannot leak into the next. The two
 * misconfiguration tests build their own deliberately broken client instead of using that one.
 */
class TwilioEmailProviderClientTestSuite {

    private static final String SEND_ENDPOINT = "https://comms.twilio.com/v1/Emails";
    private static final String ACCOUNT_SID = "ACtestsid";
    private static final String AUTH_TOKEN = "testauthtoken";
    private static final String FROM_EMAIL = "alerts@example.com";
    private static final String FROM_NAME = "Banking Alerts";

    // Copied verbatim from a real Twilio 202 rather than invented: the send is only accepted here and
    // delivered asynchronously afterwards, and operationId is the sole handle for tracing it later.
    private static final String ACCEPTED_BODY = """
            {
                "operationId": "comms_operation_01h9krwprkeee8fzqspvwy6nq8",
                "operationLocation": "https://comms.twilio.com/v1/Emails/Operations/comms_operation_01h9krwprkeee8fzqspvwy6nq8"
            }
            """;

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private TwilioEmailProviderClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        client = new TwilioEmailProviderClient(ACCOUNT_SID, AUTH_TOKEN, FROM_EMAIL, FROM_NAME, restTemplate);
    }

    /**
     * 202 Accepted, not 200, is what a successful Twilio Email send answers with — the message is
     * queued and delivered later. Returning quietly therefore means accepted-for-delivery rather
     * than delivered, and any 2xx has to be treated that way.
     */
    @Test
    @DisplayName("A 202 Accepted response completes the send without throwing - [MEANT TO PASS]")
    void send_twilioAccepts202_completesWithoutThrowing() {
        mockServer.expect(requestTo(SEND_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ACCEPTED_BODY));

        assertThatCode(() -> client.send("customer@example.com", "Your Daily Balance Summary", "<html><body>Hi</body></html>"))
                .doesNotThrowAnyException();

        mockServer.verify();
    }

    /**
     * Twilio Email authenticates with plain HTTP Basic using the account SID as the username — the
     * same credential pair the SMS client already uses. Pinning the encoded header down here is what
     * stops a refactor quietly switching to a bearer token and only discovering it against the live
     * API.
     */
    @Test
    @DisplayName("The request carries HTTP Basic auth built from the account SID and auth token - [MEANT TO PASS]")
    void send_configuredSidAndAuthToken_sendsBasicAuthHeaderOverJson() {
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString((ACCOUNT_SID + ":" + AUTH_TOKEN).getBytes(StandardCharsets.UTF_8));

        mockServer.expect(requestTo(SEND_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", expected))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ACCEPTED_BODY));

        client.send("customer@example.com", "Subject", "<html><body>Hi</body></html>");

        mockServer.verify();
    }

    /**
     * Twilio's {@code /v1/Emails} endpoint expects nested {@code from}/{@code to}/{@code content}
     * objects, not SendGrid's {@code personalizations} array. Getting the shape wrong is a 400 at
     * runtime with no compiler help, which is why the body is asserted field by field.
     */
    @Test
    @DisplayName("The JSON body matches Twilio's from/to/content shape - [MEANT TO PASS]")
    void send_htmlOnlyMessage_postsTwilioFromToContentJson() {
        mockServer.expect(requestTo(SEND_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.from.address").value(FROM_EMAIL))
                .andExpect(jsonPath("$.from.name").value(FROM_NAME))
                .andExpect(jsonPath("$.to[0].address").value("customer@example.com"))
                .andExpect(jsonPath("$.content.subject").value("Your Daily Balance Summary"))
                .andExpect(jsonPath("$.content.html").value("<html><body>Balance: $5432.10</body></html>"))
                // The plain-text alternative is left unset rather than duplicated from the HTML, so
                // @JsonInclude(NON_NULL) has to drop the key entirely — sending an explicit null here
                // is a different payload to Twilio, hence doesNotExist rather than a null value check.
                .andExpect(jsonPath("$.content.text").doesNotExist())
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ACCEPTED_BODY));

        client.send("customer@example.com", "Your Daily Balance Summary", "<html><body>Balance: $5432.10</body></html>");

        mockServer.verify();
    }

    /**
     * The exception type is the point, not just the failure: {@code RuntimeException} is what
     * {@code NotificationProviderService}'s {@code @Retryable} watches for. A checked or swallowed
     * failure would skip the retries and never record a {@code FAILED} notification.
     */
    @Test
    @DisplayName("A 400 rejection from Twilio surfaces as RuntimeException - [MEANT TO PASS]")
    void send_twilioRejectsWith400_throwsRuntimeException() {
        mockServer.expect(requestTo(SEND_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"The from address is not a verified sender identity\"}"));

        assertThatThrownBy(() -> client.send("customer@example.com", "Subject", "<html/>"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Twilio Email send failed");

        mockServer.verify();
    }

    /**
     * Same requirement as the 400 case, for the transient half of the problem: a 5xx is exactly what
     * the backoff in {@code NotificationProviderService} exists for, so it has to surface as the
     * same retryable type rather than being distinguished from a client error here.
     */
    @Test
    @DisplayName("A 5xx from Twilio also surfaces as RuntimeException - [MEANT TO PASS]")
    void send_twilioReturns500_throwsRuntimeException() {
        mockServer.expect(requestTo(SEND_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.send("customer@example.com", "Subject", "<html/>"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Twilio Email send failed");

        mockServer.verify();
    }

    /**
     * The startup guard exists so a blank credential brings the application down at boot naming the
     * exact missing property, instead of being discovered inside a Kafka listener where it becomes a
     * silently undelivered alert.
     */
    @Test
    @DisplayName("A blank from-email fails initialisation naming the property - [MEANT TO PASS]")
    void initialiseClient_blankFromEmail_throwsIllegalStateExceptionNamingFromEmailProperty() {
        TwilioEmailProviderClient misconfigured =
                new TwilioEmailProviderClient(ACCOUNT_SID, AUTH_TOKEN, "  ", FROM_NAME, restTemplate);

        assertThatThrownBy(misconfigured::initialiseClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("email.twilio.from-email");
    }

    /**
     * The credentials are shared with the SMS client, so a blank token must point at the
     * {@code sms.twilio.*} key it actually comes from. Naming an {@code email.twilio.*} property
     * here would send whoever reads the boot failure looking for config that does not exist.
     */
    @Test
    @DisplayName("A blank auth token names the shared sms.twilio.auth-token property - [MEANT TO PASS]")
    void initialiseClient_blankAuthToken_throwsIllegalStateExceptionNamingSharedSmsProperty() {
        TwilioEmailProviderClient misconfigured =
                new TwilioEmailProviderClient(ACCOUNT_SID, "", FROM_EMAIL, FROM_NAME, restTemplate);

        assertThatThrownBy(misconfigured::initialiseClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sms.twilio.auth-token");
    }

    /**
     * The complement to the two blank-property cases: the guard is only meant to catch blanks, so a
     * fully-configured client — the one built in {@code setUp()} — must pass through it untouched.
     */
    @Test
    @DisplayName("A fully configured client initialises without error - [MEANT TO PASS]")
    void initialiseClient_allRequiredPropertiesSet_completesWithoutThrowing() {
        assertThatCode(() -> client.initialiseClient()).doesNotThrowAnyException();
    }
}
