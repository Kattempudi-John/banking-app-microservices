package com.example.notificationservice;

import com.example.notificationservice.client.AccountServiceClient;
import com.example.notificationservice.client.AuthServiceClient;
import com.example.notificationservice.client.ProfileServiceClient;
import com.example.notificationservice.event.FundsTransferredEvent;
import com.example.notificationservice.model.NotificationChannel;
import org.mockito.ArgumentCaptor;
import com.example.notificationservice.model.NotificationRecord;
import com.example.notificationservice.model.NotificationStatus;
import com.example.notificationservice.model.NotificationType;
import com.example.notificationservice.repository.NotificationRecordRepository;
import com.example.notificationservice.service.NotificationProviderService;
import com.example.notificationservice.service.ProfileNotificationListener;
import com.example.notificationservice.service.TransactionAlertListener;
import com.example.notificationservice.service.TwoFactorEmailListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Covers the persistence half of the Notifications feature — each Kafka listener writing a
 * {@link NotificationRecord} alongside the dispatch call it already made — together with
 * {@code GET /api/v1/notifications}, this service's only customer-facing REST API.
 *
 * <p>{@code NotificationAlertsTestSuite} and {@code NotificationProviderServiceTestSuite} already
 * cover dispatch, thresholds and retry behaviour in depth; what is exercised here is the durable
 * record and the read API layered on top of them. The most important property in the file is that a
 * 2FA record stores a <em>masked</em> line and never the verification code: the code goes to the
 * email provider while a different string goes to the feed, and the 2FA tests assert both halves of
 * that split in the same test so the two can never silently converge.
 *
 * <h2>Slice and configuration</h2>
 *
 * <p>{@code @SpringBootTest} plus {@code @AutoConfigureMockMvc}, not {@code @WebMvcTest}: most of
 * this file drives Kafka listener beans ({@link TwoFactorEmailListener},
 * {@link TransactionAlertListener}, {@link ProfileNotificationListener}) that a web slice would
 * never instantiate, while the endpoint tests need the real controller sitting behind the real
 * security filter chain so the {@code FULL_AUTH} scope check and the JWT {@code userId} claim are
 * genuinely exercised rather than stubbed. One full context serves both halves.
 *
 * <p>The listeners are called directly as beans — no broker is involved and nothing is published to
 * {@code notification-events}. The event payloads come from the helpers at the foot of the class,
 * which reproduce field for field what auth-service and profile-service actually put on those
 * topics.
 *
 * <h2>Mocked versus real</h2>
 *
 * <ul>
 *   <li>{@link NotificationProviderService} — mocked so nothing is really emailed, and so its
 *       boolean return can be driven. That return is the only available signal of a failed send:
 *       the provider's {@code @Recover} path swallows the underlying exception, so a test cannot
 *       simulate failure by throwing.</li>
 *   <li>{@link NotificationRecordRepository} — mocked, which is why every persistence assertion
 *       here is a {@code verify(...).save(...)} on a captured entity rather than a database read.
 *       Nothing reaches Postgres and there is no transaction to roll back; ordering by
 *       {@code createdAt} and PostgreSQL enum binding are proven against the real database in
 *       {@code NotificationFeedFilterTestSuite} instead.</li>
 *   <li>{@link ProfileServiceClient} — mocked to supply the per-user alert threshold and the
 *       address a notice should be delivered to.</li>
 *   <li>{@link AccountServiceClient} and {@link AuthServiceClient} — mocked so the counterparty
 *       lookups a transaction alert now makes stay inside the test.</li>
 * </ul>
 *
 * <p>Everything else is real: both listeners' full message-building logic, the controller and its
 * query service, the exception handler that turns an unparseable filter into a 400, and the
 * security filter chain.
 *
 * <h2>Fixture state</h2>
 *
 * <p>There is no {@code @BeforeEach} or {@code @BeforeAll} and no mutable field state, so no test
 * here is order-dependent. Each test arranges its own stubbing, usually through one of the
 * {@code givenXxx} helpers. Spring resets every {@code @MockBean} between test methods, which is
 * what lets a bare {@code verify(repository).save(...)} mean "exactly one record was written by
 * this test" rather than "at least one since the class started" — and it is why
 * {@link #capturedMessage()} can assume a single save.
 *
 * <h2>External dependency</h2>
 *
 * <p>This module has no {@code src/test/resources}, so {@code @SpringBootTest} boots the real dev
 * {@code src/main/resources/application.yml}: the context runs Flyway and Hibernate against the
 * docker-compose Postgres on {@code localhost:5432} and will not start without it. That is
 * deliberate and shared by every {@code @SpringBootTest} in this module — it is not to be "fixed"
 * with a test-only configuration.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationPersistenceTestSuite {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TwoFactorEmailListener twoFactorEmailListener;

    @Autowired
    private TransactionAlertListener transactionAlertListener;

    @Autowired
    private ProfileNotificationListener profileNotificationListener;

    @MockBean
    private NotificationProviderService notificationProviderService;

    @MockBean
    private NotificationRecordRepository notificationRecordRepository;

    @MockBean
    private ProfileServiceClient profileServiceClient;

    // These two must be mocked rather than left real: they are Feign clients pointed at localhost, so
    // the suite would either make live HTTP calls to whichever services happen to be running or
    // depend on their being down, and "who owns account 2" would answer differently on a developer's
    // machine than in CI.
    @MockBean
    private AccountServiceClient accountServiceClient;

    @MockBean
    private AuthServiceClient authServiceClient;

    private static RequestPostProcessor fullAuthUser(long userId) {
        return jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", userId));
    }

    @Test
    @DisplayName("2FA email dispatch success persists a SENT notification record - [MEANT TO PASS]")
    void consumeTwoFactorRequest_emailDispatchSucceeds_savesSentEmail2faRecord() {
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);

        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEvent("user@example.com", "123456"));

        verify(notificationRecordRepository).save(argThat(record ->
                record.getUserId().equals(42L)
                        && record.getType() == NotificationType.EMAIL_2FA
                        && record.getChannel() == NotificationChannel.EMAIL
                        && record.getStatus() == NotificationStatus.SENT
        ));
    }

    /**
     * The record used to store the message body verbatim, which put a live one-time code into
     * {@code GET /api/v1/notifications} — readable off the notifications page long after the login
     * it belonged to. The email itself still has to carry the code; the audit row must not. Moving
     * 2FA from SMS to email changed the identifier being masked, not this rule.
     */
    @Test
    @DisplayName("2FA record stores a masked line, never the code itself - [MEANT TO PASS]")
    void consumeTwoFactorRequest_codeInEvent_savesMaskedLineAndNeverTheCode() {
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);

        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEvent("user@example.com", "987654"));

        // The real email keeps the code - redacting that would defeat the point of sending it.
        verify(notificationProviderService)
                .dispatchEmail(eq("user@example.com"), any(), contains("987654"));
        ArgumentCaptor<NotificationRecord> saved = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRecordRepository).save(saved.capture());
        assertThat(saved.getValue().getMessage())
                .as("a one-time code must never be persisted to the notification feed")
                .doesNotContain("987654");
        // Masked to the first character and the domain, so the row still says which address was
        // mailed without republishing it in a feed the user reopens indefinitely.
        assertThat(saved.getValue().getMessage()).isEqualTo("Verification code sent to u***@example.com.");
        assertThat(saved.getValue().getMessage()).doesNotContain("user@example.com");
    }

    /**
     * The event carries a {@code phoneNumber} too — auth-service publishes the whole contact set —
     * and it must not end up in the row now that no SMS is sent. A record naming a number nothing
     * was sent to is both wrong and an unmasked contact detail sitting in the feed. The
     * {@code never()} on {@code dispatchSms} guards the other half: that no "fall back to SMS"
     * branch has crept back in and put a live credential on a second channel.
     */
    @Test
    @DisplayName("A 2FA event's phone number triggers no SMS and stays out of the record - [MEANT TO PASS]")
    void consumeTwoFactorRequest_eventCarriesPhoneNumber_sendsNoSmsAndKeepsNumberOutOfRecord() {
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);

        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEvent("user@example.com", "123456"));

        verify(notificationProviderService, never()).dispatchSms(any(), any());
        assertThat(capturedMessage())
                .doesNotContain("+15551234567")
                .doesNotContain("4567");
    }

    @Test
    @DisplayName("2FA email dispatch failure persists a FAILED notification record - [MEANT TO FAIL]")
    void consumeTwoFactorRequest_emailDispatchReturnsFalse_savesFailedRecord() {
        // dispatchEmail's @Recover swallows the underlying exception - the false return is the only
        // signal a failure happened, exactly what the listener now checks.
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(false);

        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEvent("user@example.com", "123456"));

        verify(notificationRecordRepository).save(argThat(record -> record.getStatus() == NotificationStatus.FAILED));
    }

    /**
     * Users who registered before the email field existed have no address on file, and 2FA over
     * email makes that a login they cannot complete rather than an alert they miss. Dispatching to a
     * blank recipient would look like a success while going nowhere, so the miss is recorded as
     * {@code FAILED} — the same shape {@link TransactionAlertListener} and
     * {@code DailyBalanceSummaryJob} use.
     */
    @Test
    @DisplayName("A 2FA request with no email on file records FAILED and dispatches nothing - [MEANT TO PASS]")
    void consumeTwoFactorRequest_noEmailOnFile_savesFailedRecordWithoutDispatching() {
        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEvent(null, "123456"));

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
        ArgumentCaptor<NotificationRecord> saved = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRecordRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(42L);
        assertThat(saved.getValue().getType()).isEqualTo(NotificationType.EMAIL_2FA);
        assertThat(saved.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(saved.getValue().getMessage())
                .as("the row still must not carry the code, failed send or not")
                .doesNotContain("123456");
    }

    @Test
    @DisplayName("A blank email is treated exactly like a missing one - [MEANT TO PASS]")
    void consumeTwoFactorRequest_blankEmail_savesFailedRecordWithoutDispatching() {
        // "   " is the other shape a missing address arrives in, and it would pass a null check
        // alone - the provider would then be handed an empty recipient.
        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEvent("   ", "123456"));

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
        verify(notificationRecordRepository).save(argThat(record ->
                record.getStatus() == NotificationStatus.FAILED));
    }

    /**
     * auth-service and notification-service deploy independently, so during a rollout this listener
     * sees events both with and without {@code expiresInSeconds}. A missing key must be a fallback,
     * never an exception: the listener catches and only logs, so a throw here would silently drop
     * every code in flight — a login nobody can finish and no record explaining why.
     *
     * <p>What is asserted is that the missing key changes nothing observable: the code still reaches
     * the provider and the row is still the same masked {@code SENT} line. The fallback TTL itself
     * only alters a sentence inside the email body, which this test does not inspect.
     */
    @Test
    @DisplayName("An event with no expiresInSeconds still sends the code and records SENT - [MEANT TO PASS]")
    void consumeTwoFactorRequest_noExpiresInSeconds_stillDispatchesCodeAndSavesSentMaskedRecord() {
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);

        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEventWithoutTtl("user@example.com", "123456"));

        verify(notificationProviderService).dispatchEmail(eq("user@example.com"), any(), contains("123456"));
        verify(notificationRecordRepository).save(argThat(record ->
                record.getStatus() == NotificationStatus.SENT
                        && record.getMessage().equals("Verification code sent to u***@example.com.")));
    }

    /**
     * The {@code notification-events} topic carries more than one kind of event, so the action
     * filter is the whole guard against this listener mailing a code for something that was never a
     * 2FA request.
     */
    @Test
    @DisplayName("An event with another action is ignored entirely - [MEANT TO PASS]")
    void consumeTwoFactorRequest_actionOtherThanTwoFaRequested_dispatchesNothingAndSavesNothing() {
        // The action this listener answered to before the switch to email. auth-service no longer
        // publishes it, and if an old producer somehow did, nothing here should act on it.
        Map<String, Object> legacyEvent = Map.of(
                "action", "SMS_2FA_REQUESTED", "userId", "42",
                "email", "user@example.com", "phoneNumber", "+15551234567", "code", "123456");

        twoFactorEmailListener.consumeTwoFactorRequest(legacyEvent);

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
        verify(notificationRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("Transaction alert above threshold persists a SENT notification record - [MEANT TO PASS]")
    void consumeTransferEvent_amountAboveAlertThreshold_savesSentTransactionAlertRecord() {
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(42L, new BigDecimal("100.00"), true, 8, "UTC", "alerts@example.com"));
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);
        FundsTransferredEvent event = new FundsTransferredEvent(42L, 1L, 2L, new BigDecimal("500.00"), UUID.randomUUID());

        transactionAlertListener.consumeTransferEvent(event);

        verify(notificationRecordRepository).save(argThat(record ->
                record.getUserId().equals(42L)
                        && record.getType() == NotificationType.TRANSACTION_ALERT
                        && record.getChannel() == NotificationChannel.EMAIL
                        && record.getStatus() == NotificationStatus.SENT
        ));
    }

    @Test
    @DisplayName("Transaction alert below threshold does not persist a notification record - [MEANT TO FAIL]")
    void consumeTransferEvent_amountBelowAlertThreshold_savesNoRecord() {
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(42L, new BigDecimal("1000.00"), true, 8, "UTC", "alerts@example.com"));
        FundsTransferredEvent event = new FundsTransferredEvent(42L, 1L, 2L, new BigDecimal("50.00"), UUID.randomUUID());

        transactionAlertListener.consumeTransferEvent(event);

        verify(notificationRecordRepository, never()).save(any());
    }

    /**
     * Same missing-address story as 2FA, one channel further down. Dispatching to the old fabricated
     * {@code user_<id>@bank.com} would have looked like a success while going nowhere, so the
     * listener now records the miss as {@code FAILED} and sends nothing.
     */
    @Test
    @DisplayName("Transaction alert for a user with no email records FAILED and dispatches nothing - [MEANT TO PASS]")
    void consumeTransferEvent_noEmailOnFile_savesFailedRecordWithoutDispatching() {
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(42L, new BigDecimal("100.00"), true, 8, "UTC", null));

        transactionAlertListener.consumeTransferEvent(new FundsTransferredEvent(
                42L, 1L, 2L, new BigDecimal("500.00"), UUID.randomUUID()));

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
        verify(notificationRecordRepository).save(argThat(record ->
                record.getUserId().equals(42L) && record.getStatus() == NotificationStatus.FAILED
        ));
    }

    /**
     * Closes a pre-existing gap: this listener used to only log a line, never actually calling
     * {@link NotificationProviderService} despite its own comment claiming to. The
     * {@code dispatchEmail} verify is what proves the notice really leaves the service rather than
     * merely being recorded as though it had.
     */
    @Test
    @DisplayName("Profile security update dispatches an email and persists a SENT record - [MEANT TO PASS]")
    void consumeProfileUpdate_contactInfoUpdatedEvent_dispatchesEmailAndSavesSentProfileSecurityRecord() {
        // Unlike the transaction alert above, this listener has no preferences object handed to it,
        // so it fetches one purely to resolve where the notice should be delivered.
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(42L, new BigDecimal("100.00"), true, 8, "UTC", "alerts@example.com"));
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);
        Map<String, Object> event = Map.of("userId", "42", "eventType", "CONTACT_INFO_UPDATED");

        profileNotificationListener.consumeProfileUpdate(event);

        verify(notificationProviderService).dispatchEmail(eq("alerts@example.com"), any(), any());
        verify(notificationRecordRepository).save(argThat(record ->
                record.getUserId().equals(42L)
                        && record.getType() == NotificationType.PROFILE_SECURITY
                        && record.getStatus() == NotificationStatus.SENT
        ));
    }

    @Test
    @DisplayName("GET /api/v1/notifications returns the caller's notifications resolved from their JWT userId - [MEANT TO PASS]")
    void getNotifications_authenticatedCaller_returnsOnlyRecordsForJwtUserId() throws Exception {
        // The eq(42L) is the scoping assertion: the repository answers for user 42 and no other, so
        // the endpoint can only have taken the id from the token's userId claim.
        given(notificationRecordRepository.findByUserId(eq(42L), any()))
                .willReturn(new PageImpl<>(List.of(buildRecord(42L))));

        mockMvc.perform(get("/api/v1/notifications").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("SMS_2FA"));
    }

    @Test
    @DisplayName("GET /api/v1/notifications answers 4xx for an unauthenticated caller - [MEANT TO FAIL]")
    void getNotifications_unauthenticatedCaller_returns4xx() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().is4xxClientError());
    }

    // ==========================================
    // What the stored profile notice actually says
    // ==========================================

    /**
     * The whole point of reading the {@code changes} map profile-service has always published: the
     * notice used to read "an update of type 'CONTACT_INFO_CHANGE' was made to your profile", which
     * names neither the field that moved nor what it moved from.
     */
    @Test
    @DisplayName("Profile notice names only the fields that actually changed, before and after - [MEANT TO PASS]")
    void consumeProfileUpdate_someFieldsChanged_describesOnlyChangedFieldsWithBeforeAndAfter() {
        givenProfileEmailOnFile();

        profileNotificationListener.consumeProfileUpdate(contactInfoEvent(
                oldState("+15712856947", "123 Main St", "Fairfax"),
                newState("+1 (571) 285-1234", "123 Main St", "Reston")));

        String message = capturedMessage();
        assertThat(message).isEqualTo(
                "Your profile was updated: phone number changed from ***6947 to ***1234; "
                        + "city changed from Fairfax to Reston.");
        // addressLine1 was resubmitted unchanged, so it must not appear at all - a form that echoes
        // back every field it was given is the noise this message replaces.
        assertThat(message).doesNotContain("address line 1");
    }

    /**
     * A user who opens the form, changes nothing and presses save still produces an event. Listing
     * all eight fields as "changed" there would train them to ignore the notice entirely.
     */
    @Test
    @DisplayName("Profile notice claims no change when the form was resubmitted unedited - [MEANT TO PASS]")
    void consumeProfileUpdate_formResubmittedUnedited_describesNoFieldAsChanged() {
        givenProfileEmailOnFile();

        profileNotificationListener.consumeProfileUpdate(contactInfoEvent(
                oldState("+15712856947", "123 Main St", "Fairfax"),
                newState("+15712856947", "123 Main St", "Fairfax")));

        String message = capturedMessage();
        assertThat(message).doesNotContain("changed from");
        assertThat(message).doesNotContain("set to");
    }

    /**
     * A field being filled in for the first time is a different sentence from a field being edited.
     * "city changed from null to Reston" is what the naive version of this reads like.
     */
    @Test
    @DisplayName("Profile notice reads naturally for a field that was previously empty - [MEANT TO PASS]")
    void consumeProfileUpdate_previouslyEmptyField_describesItAsSetRatherThanChangedFromNull() {
        givenProfileEmailOnFile();

        profileNotificationListener.consumeProfileUpdate(contactInfoEvent(
                oldState("+15712856947", "123 Main St", null),
                newState("+15712856947", "123 Main St", "Reston")));

        String message = capturedMessage();
        assertThat(message).contains("city set to Reston");
        assertThat(message).doesNotContain("null");
    }

    /**
     * The privacy half of this message. The record is served back by
     * {@code GET /api/v1/notifications} every time the user reopens the page, which is exactly why
     * V3 scrubbed 2FA codes out of stored rows — a phone number written in full would sit in the
     * feed indefinitely.
     */
    @Test
    @DisplayName("Profile notice masks the phone number and stores no full number anywhere - [MEANT TO PASS]")
    void consumeProfileUpdate_phoneNumberChanged_recordsOnlyMaskedLastFourDigits() {
        givenProfileEmailOnFile();

        profileNotificationListener.consumeProfileUpdate(contactInfoEvent(
                oldState("+15712856947", "123 Main St", "Fairfax"),
                newState("571-285-1234", "123 Main St", "Fairfax")));

        String message = capturedMessage();
        assertThat(message).contains("phone number changed from ***6947 to ***1234");
        // Both formattings of the new number are checked because the event supplies it dashed while
        // the old one arrives in E.164 - masking that only handled one shape would slip through.
        assertThat(message)
                .as("neither the old nor the new number may be recoverable from a stored record")
                .doesNotContain("+15712856947")
                .doesNotContain("5712856947")
                .doesNotContain("571-285-1234")
                .doesNotContain("2851234");
    }

    /**
     * This runs on a Kafka listener, so an exception thrown while describing the change would be
     * caught upstream and the notification would simply never be written. A vague notice beats none.
     */
    @Test
    @DisplayName("Profile notice falls back to the generic wording when the changes map is malformed - [MEANT TO PASS]")
    void consumeProfileUpdate_changesMapIsNotAMap_savesGenericWordingWithoutThrowing() {
        givenProfileEmailOnFile();
        Map<String, Object> garbage = new HashMap<>();
        garbage.put("userId", "42");
        garbage.put("eventType", "CONTACT_INFO_CHANGE");
        garbage.put("changes", "this is not a map at all");

        assertThatCode(() -> profileNotificationListener.consumeProfileUpdate(garbage))
                .doesNotThrowAnyException();

        assertThat(capturedMessage())
                .isEqualTo("Dear customer, an update of type 'CONTACT_INFO_CHANGE' was made to your profile.");
    }

    @Test
    @DisplayName("Profile notice falls back to the generic wording when no changes map is present - [MEANT TO PASS]")
    void consumeProfileUpdate_noChangesMapOnEvent_savesGenericWording() {
        givenProfileEmailOnFile();

        // The shape a legacy publisher (or a different profile event type) sends: no changes at all.
        profileNotificationListener.consumeProfileUpdate(
                Map.of("userId", "42", "eventType", "CONTACT_INFO_CHANGE"));

        assertThat(capturedMessage())
                .isEqualTo("Dear customer, an update of type 'CONTACT_INFO_CHANGE' was made to your profile.");
    }

    // ==========================================
    // What the stored transaction alert actually says
    // ==========================================

    @Test
    @DisplayName("Transaction alert names the amount, both masked accounts and the counterparty - [MEANT TO PASS]")
    void consumeTransferEvent_counterpartyResolves_recordsAmountMaskedAccountsAndPayeeName() {
        givenAlertThresholdOf("100.00");
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);
        given(accountServiceClient.getAccountOwner(770015570L))
                .willReturn(new AccountServiceClient.AccountOwnerResponse(77L));
        given(authServiceClient.getDisplayName(77L))
                .willReturn(new AuthServiceClient.DisplayNameResponse(77L, "Mark"));

        transactionAlertListener.consumeTransferEvent(new FundsTransferredEvent(
                42L, 990013400L, 770015570L, new BigDecimal("1000.00"), UUID.randomUUID()));

        String message = capturedMessage();
        assertThat(message).contains("$1,000.00");
        assertThat(message).contains("........3400");
        assertThat(message).contains("........5570");
        assertThat(message).contains("Mark");
        // Same masking rule the rest of the project applies to account numbers - the stored row must
        // not carry a whole account reference for either side of the transfer.
        assertThat(message)
                .doesNotContain("990013400")
                .doesNotContain("770015570");
    }

    /**
     * The counterparty's name is a nicety; the alert is not. transaction-service's
     * {@code resolveRecipientName} degrades the same way for the same reason.
     */
    @Test
    @DisplayName("Transaction alert still sends with the masked account when the name lookup fails - [MEANT TO PASS]")
    void consumeTransferEvent_displayNameLookupThrows_stillSendsAlertWithMaskedAccount() {
        givenAlertThresholdOf("100.00");
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);
        given(accountServiceClient.getAccountOwner(770015570L))
                .willReturn(new AccountServiceClient.AccountOwnerResponse(77L));
        given(authServiceClient.getDisplayName(77L)).willThrow(new RuntimeException("auth-service unavailable"));

        transactionAlertListener.consumeTransferEvent(new FundsTransferredEvent(
                42L, 990013400L, 770015570L, new BigDecimal("1000.00"), UUID.randomUUID()));

        verify(notificationProviderService).dispatchEmail(eq("alerts@example.com"), any(), any());
        verify(notificationRecordRepository).save(argThat(record ->
                record.getStatus() == NotificationStatus.SENT
                        && record.getMessage().contains("........5570")));
    }

    /**
     * An owner lookup that fails takes the display name with it — there is no user id left to ask
     * about — and the alert still has to go out. The {@code never()} on {@code getDisplayName} is
     * what proves the listener gives up on the name rather than calling auth-service with a null id.
     */
    @Test
    @DisplayName("Transaction alert still sends when the owner lookup itself fails - [MEANT TO PASS]")
    void consumeTransferEvent_accountOwnerLookupThrows_stillSendsAlertAndSkipsNameLookup() {
        givenAlertThresholdOf("100.00");
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);
        given(accountServiceClient.getAccountOwner(any())).willThrow(new RuntimeException("account-service unavailable"));

        transactionAlertListener.consumeTransferEvent(new FundsTransferredEvent(
                42L, 990013400L, 770015570L, new BigDecimal("1000.00"), UUID.randomUUID()));

        verify(notificationProviderService).dispatchEmail(eq("alerts@example.com"), any(), any());
        assertThat(capturedMessage()).contains("........5570");
        verify(authServiceClient, never()).getDisplayName(any());
    }

    /**
     * Moving money between your own checking and savings is not a payment to a stranger, and an
     * alert that describes it as one is the kind of thing that gets a support call.
     */
    @Test
    @DisplayName("A transfer between the user's own accounts is worded as such - [MEANT TO PASS]")
    void consumeTransferEvent_bothAccountsOwnedByTheSameUser_wordsAlertAsMoveBetweenOwnAccounts() {
        givenAlertThresholdOf("100.00");
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);
        // Owner 42 is the same user the event names as the sender: both ends of the transfer.
        given(accountServiceClient.getAccountOwner(770015570L))
                .willReturn(new AccountServiceClient.AccountOwnerResponse(42L));

        transactionAlertListener.consumeTransferEvent(new FundsTransferredEvent(
                42L, 990013400L, 770015570L, new BigDecimal("1000.00"), UUID.randomUUID()));

        String message = capturedMessage();
        assertThat(message).contains("moved between your own accounts");
        assertThat(message).contains("........3400");
        assertThat(message).contains("........5570");
        // Their own name has no business appearing as a recipient, so the lookup is never made.
        verify(authServiceClient, never()).getDisplayName(any());
    }

    // ==========================================
    // Filtering on the feed
    // ==========================================

    @Test
    @DisplayName("GET /api/v1/notifications accepts all five filters at once and answers from the Specification query - [MEANT TO PASS]")
    void getNotifications_allFiveFiltersSupplied_returns200FromSpecificationQuery() throws Exception {
        given(notificationRecordRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(buildRecord(42L))));

        mockMvc.perform(get("/api/v1/notifications")
                        .param("type", "SMS_2FA")
                        .param("channel", "SMS")
                        .param("status", "SENT")
                        .param("from", "2020-01-01T00:00:00")
                        .param("to", "2030-01-01T00:00:00")
                        .with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("SMS_2FA"));
        // A filtered request goes through the Specification, never through the unfiltered lookup.
        verify(notificationRecordRepository, never()).findByUserId(any(), any());
    }

    /**
     * The response is still a Spring {@code Page} of the same DTO, filters or not — the existing
     * page reads {@code content}/{@code totalElements} and must keep working without being taught
     * anything new.
     */
    @Test
    @DisplayName("A filtered response keeps the unfiltered response shape - [MEANT TO PASS]")
    void getNotifications_singleFilterSupplied_returns200WithUnfilteredPageShape() throws Exception {
        given(notificationRecordRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(buildRecord(42L))));

        mockMvc.perform(get("/api/v1/notifications").param("status", "SENT").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].channel").value("SMS"))
                .andExpect(jsonPath("$.content[0].status").value("SENT"))
                // createdAt is deliberately not asserted here: buildRecord is never persisted, so
                // @PrePersist never runs and the field is legitimately null on this fixture. The real
                // ordering-by-createdAt behaviour is proven against the database in
                // NotificationFeedFilterTestSuite instead.
                .andExpect(jsonPath("$.content[0].message").exists());
    }

    @Test
    @DisplayName("An unparseable enum filter is a 400 naming the accepted values - [MEANT TO PASS]")
    void getNotifications_unparseableEnumFilter_returns400ListingAcceptedValues() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").param("type", "NOT_A_TYPE").with(fullAuthUser(42)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("NOT_A_TYPE")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("TRANSACTION_ALERT")));
    }

    @Test
    @DisplayName("An unparseable date filter is a 400, not a 500 - [MEANT TO PASS]")
    void getNotifications_unparseableDateFilter_returns400NamingIsoDateTime() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").param("from", "last tuesday").with(fullAuthUser(42)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ISO date-time")));
    }

    // ==========================================
    // Helpers
    // ==========================================

    // The exact envelope auth-service puts on notification-events for a login that needs a code:
    // action, userId, email, phoneNumber, the code itself and the TTL the login screen counts down.
    // phoneNumber is still published and this listener no longer reads it - a HashMap rather than
    // Map.of because a null email is a real state (a user who registered before the field existed)
    // and Map.of rejects nulls outright. expiresInSeconds is a String, as the whole payload is.
    private Map<String, Object> twoFactorEvent(String email, String code) {
        Map<String, Object> event = twoFactorEventWithoutTtl(email, code);
        event.put("expiresInSeconds", "180");
        return event;
    }

    // The same envelope as an auth-service that has not been redeployed yet publishes it. The two
    // services ship independently, so this shape is live traffic during a rollout, not a relic.
    private Map<String, Object> twoFactorEventWithoutTtl(String email, String code) {
        Map<String, Object> event = new HashMap<>();
        event.put("action", "TWO_FA_REQUESTED");
        event.put("userId", "42");
        event.put("email", email);
        event.put("phoneNumber", "+15551234567");
        event.put("code", code);
        return event;
    }

    private void givenProfileEmailOnFile() {
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(
                        42L, new BigDecimal("100.00"), true, 8, "UTC", "alerts@example.com"));
        given(notificationProviderService.dispatchEmail(any(), any(), any())).willReturn(true);
    }

    private void givenAlertThresholdOf(String threshold) {
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new ProfileServiceClient.UserPreferenceResponse(
                        42L, new BigDecimal(threshold), true, 8, "UTC", "alerts@example.com"));
    }

    // The exact envelope ProfileManagementService.publishContactInfoChangedEvent puts on the
    // profile-events topic: userId, eventType, and a changes map holding the before and after states.
    private Map<String, Object> contactInfoEvent(Map<String, Object> oldState, Map<String, Object> newState) {
        Map<String, Object> changes = new HashMap<>();
        changes.put("old", oldState);
        changes.put("new", newState);

        Map<String, Object> event = new HashMap<>();
        event.put("userId", "42");
        event.put("eventType", "CONTACT_INFO_CHANGE");
        event.put("changes", changes);
        return event;
    }

    // profile-service's captureOldContactState snapshots these three fields and no others, so the
    // before-state genuinely has holes in it - a HashMap rather than Map.of because a null value here
    // is the real shape of a field the user had never filled in.
    private Map<String, Object> oldState(String phoneNumber, String addressLine1, String city) {
        Map<String, Object> state = new HashMap<>();
        state.put("phoneNumber", phoneNumber);
        state.put("addressLine1", addressLine1);
        state.put("city", city);
        return state;
    }

    // The whole submitted form, which is what the "new" side carries (the serialized DTO).
    private Map<String, Object> newState(String phoneNumber, String addressLine1, String city) {
        Map<String, Object> state = new HashMap<>();
        state.put("legalName", "Jane Doe");
        state.put("dateOfBirth", "1990-01-01");
        state.put("phoneNumber", phoneNumber);
        state.put("addressLine1", addressLine1);
        state.put("addressLine2", null);
        state.put("city", city);
        state.put("state", "VA");
        state.put("zipCode", "20190");
        return state;
    }

    // Relies on exactly one save having happened in this test - true because Spring resets the
    // repository mock between test methods.
    private String capturedMessage() {
        ArgumentCaptor<NotificationRecord> saved = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRecordRepository).save(saved.capture());
        return saved.getValue().getMessage();
    }

    private NotificationRecord buildRecord(Long userId) {
        NotificationRecord record = new NotificationRecord();
        record.setUserId(userId);
        record.setType(NotificationType.SMS_2FA);
        record.setChannel(NotificationChannel.SMS);
        record.setMessage("Verification code sent to ***4567.");
        record.setStatus(NotificationStatus.SENT);
        return record;
    }
}
