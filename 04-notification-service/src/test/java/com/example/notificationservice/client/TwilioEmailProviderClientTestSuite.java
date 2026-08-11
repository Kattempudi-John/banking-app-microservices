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

// Deliberately a plain JUnit test with no Spring context. This service has no test datasource
// configured and NotificationRecord binds real PostgreSQL enum types that H2 cannot emulate, so
// @SpringBootTest here would need a live database to check an HTTP client that has nothing to do
// with one. MockRestServiceServer covers the whole contract without either.
//
// Sits in the client package rather than beside the other suites one level up because it reaches the
// package-private test constructor (to inject a mocked RestTemplate) and initialiseClient() directly
// - neither is worth widening to public purely to relocate a test file.
class TwilioEmailProviderClientTestSuite {

    private static final String SEND_ENDPOINT = "https://comms.twilio.com/v1/Emails";
    private static final String ACCOUNT_SID = "ACtestsid";
    private static final String AUTH_TOKEN = "testauthtoken";
    private static final String FROM_EMAIL = "alerts@example.com";
    private static final String FROM_NAME = "Banking Alerts";

    // Twilio's real 202 body - the send is accepted here and processed asynchronously afterwards.
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

    // the happy path, a 202 accepted is what twilio answers on success, anything in the 2xx range
    // means accepted for delivery and the client should return quietly without throwing
    @Test
    @DisplayName("Block 1: A 202 Accepted response completes the send without throwing - [MEANT TO PASS]")
    void testBlock1_acceptedResponse_completesQuietly() {
        mockServer.expect(requestTo(SEND_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ACCEPTED_BODY));

        assertThatCode(() -> client.send("customer@example.com", "Your Daily Balance Summary", "<html><body>Hi</body></html>"))
                .doesNotThrowAnyException();

        mockServer.verify();
    }

    // twilio email authenticates with plain http basic using the account sid as the username, the
    // same credential pair the sms client already uses, this pins that wire format down so a refactor
    // cannot quietly switch it to a bearer token and only find out against the live api
    @Test
    @DisplayName("Block 2: The request carries HTTP Basic auth built from the account SID and auth token - [MEANT TO PASS]")
    void testBlock2_requestUsesBasicAuthFromTwilioCredentials() {
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

    // the json body shape twilio's /v1/Emails endpoint expects, nested from/to/content objects rather
    // than sendgrid's personalizations array, getting this wrong is a 400 at runtime with no compiler help
    @Test
    @DisplayName("Block 3: The JSON body matches Twilio's from/to/content shape - [MEANT TO PASS]")
    void testBlock3_requestBodyMatchesTwilioSchema() {
        mockServer.expect(requestTo(SEND_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.from.address").value(FROM_EMAIL))
                .andExpect(jsonPath("$.from.name").value(FROM_NAME))
                .andExpect(jsonPath("$.to[0].address").value("customer@example.com"))
                .andExpect(jsonPath("$.content.subject").value("Your Daily Balance Summary"))
                .andExpect(jsonPath("$.content.html").value("<html><body>Balance: $5432.10</body></html>"))
                // the plain-text alternative is left unset rather than duplicated from the HTML, and
                // NON_NULL has to keep the key out of the payload entirely instead of sending null
                .andExpect(jsonPath("$.content.text").doesNotExist())
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ACCEPTED_BODY));

        client.send("customer@example.com", "Your Daily Balance Summary", "<html><body>Balance: $5432.10</body></html>");

        mockServer.verify();
    }

    // a rejected send has to come back out as a runtimeexception specifically, that is the type
    // NotificationProviderService's @retryable watches for, a checked or swallowed failure would skip
    // the retries and never record a FAILED notification
    @Test
    @DisplayName("Block 4: A rejected send throws RuntimeException so the retry layer catches it - [MEANT TO PASS]")
    void testBlock4_rejectedSend_throwsRuntimeException() {
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

    // same requirement for a server side outage, a 5xx is transient and exactly the case the backoff
    // in NotificationProviderService exists for, so it has to surface as the retryable type too
    @Test
    @DisplayName("Block 5: A 5xx from Twilio also surfaces as RuntimeException - [MEANT TO PASS]")
    void testBlock5_serverError_throwsRuntimeException() {
        mockServer.expect(requestTo(SEND_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.send("customer@example.com", "Subject", "<html/>"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Twilio Email send failed");

        mockServer.verify();
    }

    // the startup guard, a blank credential has to fail at boot naming the exact missing property
    // rather than being discovered inside a kafka listener where it becomes a silently undelivered alert
    @Test
    @DisplayName("Block 6: A blank from-email fails initialisation naming the property - [MEANT TO PASS]")
    void testBlock6_blankFromEmail_failsInitialisation() {
        TwilioEmailProviderClient misconfigured =
                new TwilioEmailProviderClient(ACCOUNT_SID, AUTH_TOKEN, "  ", FROM_NAME, restTemplate);

        assertThatThrownBy(misconfigured::initialiseClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("email.twilio.from-email");
    }

    // the credentials are shared with the sms client, so a blank one has to point at the sms.twilio.*
    // key it actually comes from, naming an email.twilio.* property here would send someone looking
    // for config that does not exist
    @Test
    @DisplayName("Block 7: A blank auth token names the shared sms.twilio.auth-token property - [MEANT TO PASS]")
    void testBlock7_blankAuthToken_namesSharedSmsProperty() {
        TwilioEmailProviderClient misconfigured =
                new TwilioEmailProviderClient(ACCOUNT_SID, "", FROM_EMAIL, FROM_NAME, restTemplate);

        assertThatThrownBy(misconfigured::initialiseClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sms.twilio.auth-token");
    }

    // a fully configured client has to initialise cleanly, the guard is only meant to catch blanks
    @Test
    @DisplayName("Block 8: A fully configured client initialises without error - [MEANT TO PASS]")
    void testBlock8_fullyConfiguredClient_initialisesCleanly() {
        assertThatCode(() -> client.initialiseClient()).doesNotThrowAnyException();
    }
}
