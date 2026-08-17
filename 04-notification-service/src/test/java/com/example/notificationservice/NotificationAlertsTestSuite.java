package com.example.notificationservice;

import com.example.notificationservice.client.AccountServiceClient;
import com.example.notificationservice.client.AccountServiceClient.UserAggregateBalanceResponse;
import com.example.notificationservice.client.ProfileServiceClient;
import com.example.notificationservice.client.ProfileServiceClient.UserPreferenceResponse;
import com.example.notificationservice.event.FundsTransferredEvent;
import com.example.notificationservice.job.DailyBalanceSummaryJob;
import com.example.notificationservice.model.NotificationChannel;
import com.example.notificationservice.model.NotificationRecord;
import com.example.notificationservice.model.NotificationStatus;
import com.example.notificationservice.model.NotificationType;
import com.example.notificationservice.repository.NotificationRecordRepository;
import com.example.notificationservice.service.NotificationProviderService;
import com.example.notificationservice.service.TransactionAlertListener;
import com.example.notificationservice.service.TwoFactorEmailListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Covers the three components that decide <em>when</em> an outbound notification is sent and
 * <em>what</em> it says: {@link TransactionAlertListener} (Kafka-driven transfer alerts),
 * {@link DailyBalanceSummaryJob} (the scheduled per-user summary sweep) and
 * {@link TwoFactorEmailListener} (2FA codes, which now travel by email rather than SMS).
 *
 * <h2>Slice and why it is this wide</h2>
 * The three units under test are ordinary beans rather than web controllers, so there is no
 * narrower slice worth reaching for — {@code @WebMvcTest} would load an MVC layer none of them
 * live in, and {@code @DataJpaTest} would load a persistence layer this suite deliberately mocks
 * away. {@code @SpringBootTest} boots the full context and hands back the three real beans by
 * {@code @Autowired}, so the listeners' own threshold, timezone and formatting logic is exercised
 * for real; only their collaborators are replaced.
 *
 * <h2>Mocked versus real</h2>
 * Real: the three components above, plus everything Spring wires into them.
 * <ul>
 *   <li>{@code ProfileServiceClient} and {@code AccountServiceClient} are {@code @MockBean} —
 *       both are Feign clients onto other services, so mocking them removes the network and makes
 *       preference rows and balances a fixture rather than the state of a running profile-service.</li>
 *   <li>{@code NotificationProviderService} is {@code @MockBean} because this suite asks who was
 *       mailed and what the body said, not how a send is retried. Its own dispatch, retry and
 *       {@code @Recover} behaviour is covered by {@code NotificationProviderServiceTestSuite};
 *       exercising the real AOP-proxied bean here would conflict with verifying its callers.</li>
 *   <li>{@code NotificationRecordRepository} is {@code @MockBean} so that assertions on what gets
 *       recorded are a direct {@code verify(...).save(captor.capture())} rather than a
 *       save-then-query round trip.</li>
 *   <li>{@link Clock} is {@code @MockBean}, and it is the design point of this suite. The summary
 *       job matches each user's own chosen hour in each user's own timezone, which against the real
 *       wall clock is only true for one hour a day. {@code DailyBalanceSummaryJob} reads
 *       {@code Instant.now(clock)} precisely so a fixed instant can be injected here; without it
 *       every summary test would be unrunnable 23 hours out of 24.</li>
 * </ul>
 *
 * <h2>Fixture state and lifecycle</h2>
 * The instants are {@code static final} constants, but the only mutable shared state is the mocked
 * {@code Clock}, re-stubbed by {@code @BeforeEach} ({@code setUpClock}) to 08:00 America/New_York
 * before every test. There is no {@code @BeforeAll}: Mockito resets the {@code @MockBean}s between
 * tests, so per-test stubbing is required, and tests that need a different moment simply re-stub
 * {@code clock.instant()} in their own arrange block.
 *
 * <h2>External dependencies</h2>
 * No {@code @TestPropertySource} and no {@code @DirtiesContext}: nothing here mutates the context,
 * and the defaults under test are the ones shipped in {@code application.yml}. The module has no
 * {@code src/test/resources}, so the context boots the real dev config and needs the Docker
 * Postgres to be up — Hibernate and Flyway must connect for the context to start. Nothing in this
 * suite reads or writes a row, though: the repository is mocked, so there is no transaction around
 * a test and therefore no rollback behaviour to rely on.
 */
@SpringBootTest
class NotificationAlertsTestSuite {

    @Autowired
    private TransactionAlertListener transactionAlertListener;

    @Autowired
    private DailyBalanceSummaryJob dailyBalanceSummaryJob;

    @Autowired
    private TwoFactorEmailListener twoFactorEmailListener;

    @MockBean
    private ProfileServiceClient profileServiceClient;

    @MockBean
    private AccountServiceClient accountServiceClient;

    @MockBean
    private NotificationProviderService notificationProviderService;

    @MockBean
    private NotificationRecordRepository notificationRecordRepository;

    @MockBean
    private Clock clock;

    // 2024-01-15T13:00:00Z is 08:00 local in America/New_York (EST, UTC-5, no DST in January).
    private static final Instant EIGHT_AM_NEW_YORK = Instant.parse("2024-01-15T13:00:00Z");
    // The same wall clock one hour on, 09:00 in New York - the very next run of an hourly cron.
    private static final Instant NINE_AM_NEW_YORK = Instant.parse("2024-01-15T14:00:00Z");
    // July, when New York is on EDT (UTC-4), so 08:00 local is a different instant than it is in
    // January. A job matching on a stored offset instead of a ZonedDateTime gets this one wrong.
    private static final Instant EIGHT_AM_NEW_YORK_SUMMER = Instant.parse("2024-07-15T12:00:00Z");
    private static final String NEW_YORK_ZONE = "America/New_York";

    @BeforeEach
    void setUpClock() {
        given(clock.instant()).willReturn(EIGHT_AM_NEW_YORK);
    }

    @Test
    @DisplayName("Block 1: Transaction at/above the user's threshold dispatches an alert - [MEANT TO PASS]")
    void consumeTransferEvent_amountAtOrAboveThreshold_dispatchesEmailToTheUsersAddress() {
        // userId (42L) is deliberately distinct from fromAccountId/toAccountId (501L/502L) so this
        // test cannot pass by accident if the listener regresses to using an account ID again.
        FundsTransferredEvent event = new FundsTransferredEvent(42L, 501L, 502L, new BigDecimal("150.00"), UUID.randomUUID());
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new UserPreferenceResponse(42L, new BigDecimal("100.00"), true, 8, "America/New_York", "alerts@example.com"));

        transactionAlertListener.consumeTransferEvent(event);

        verify(notificationProviderService).dispatchEmail(eq("alerts@example.com"), anyString(), anyString());
        verify(profileServiceClient, never()).getUserPreferences(501L);
    }

    @Test
    @DisplayName("Block 2: Transaction below the user's threshold does not dispatch an alert - [MEANT TO PASS]")
    void consumeTransferEvent_amountBelowThreshold_dispatchesNoEmail() {
        FundsTransferredEvent event = new FundsTransferredEvent(42L, 501L, 502L, new BigDecimal("50.00"), UUID.randomUUID());
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new UserPreferenceResponse(42L, new BigDecimal("100.00"), true, 8, "America/New_York", "alerts@example.com"));

        transactionAlertListener.consumeTransferEvent(event);

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
    }

    @Test
    @DisplayName("Block 3: Missing preferences skips the alert without throwing - [MEANT TO PASS]")
    void consumeTransferEvent_noPreferencesOnFile_skipsTheAlertWithoutThrowing() {
        FundsTransferredEvent event = new FundsTransferredEvent(42L, 501L, 502L, new BigDecimal("500.00"), UUID.randomUUID());
        given(profileServiceClient.getUserPreferences(42L)).willReturn(null);

        assertThatCode(() -> transactionAlertListener.consumeTransferEvent(event)).doesNotThrowAnyException();

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
    }

    // A single bad event or a temporary outage must never kill the Kafka consumer thread, which
    // would silently stop every other user's alerts too.
    @Test
    @DisplayName("Block 4: Profile Service failure is swallowed so the Kafka consumer thread survives - [MEANT TO PASS]")
    void consumeTransferEvent_profileServiceThrows_swallowsTheFailureAndDispatchesNothing() {
        FundsTransferredEvent event = new FundsTransferredEvent(42L, 501L, 502L, new BigDecimal("500.00"), UUID.randomUUID());
        given(profileServiceClient.getUserPreferences(42L)).willThrow(new RuntimeException("Profile Service unavailable"));

        assertThatCode(() -> transactionAlertListener.consumeTransferEvent(event)).doesNotThrowAnyException();

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
    }

    @Test
    @DisplayName("Block 5: Listener queries preferences by the event's userId, never by an account ID - [MEANT TO PASS]")
    void consumeTransferEvent_userIdDiffersFromBothAccountIds_looksUpPreferencesByUserId() {
        // 111L and 222L are left unstubbed on purpose: a lookup by either account ID returns null
        // preferences and no email, so the final dispatch assertion also fails if the ID regresses.
        FundsTransferredEvent event = new FundsTransferredEvent(777L, 111L, 222L, new BigDecimal("200.00"), UUID.randomUUID());
        given(profileServiceClient.getUserPreferences(777L))
                .willReturn(new UserPreferenceResponse(777L, new BigDecimal("100.00"), true, 8, "UTC", "alerts@example.com"));

        transactionAlertListener.consumeTransferEvent(event);

        verify(profileServiceClient).getUserPreferences(777L);
        verify(profileServiceClient, never()).getUserPreferences(111L);
        verify(profileServiceClient, never()).getUserPreferences(222L);
        verify(notificationProviderService).dispatchEmail(eq("alerts@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("Block 7: Opted-in users with a matching balance receive a summary email - [MEANT TO PASS]")
    void processDailySummaries_optedInUserDueThisHour_dispatchesOneSummaryEmail() {
        given(profileServiceClient.getAllUsersForDailySummary())
                .willReturn(List.of(new UserPreferenceResponse(100L, new BigDecimal("100.00"), true, 8, NEW_YORK_ZONE, "summary@example.com")));
        given(accountServiceClient.getAggregateBalancesBatch(eq(List.of(100L))))
                .willReturn(List.of(new UserAggregateBalanceResponse(100L, new BigDecimal("5432.10"))));

        dailyBalanceSummaryJob.processDailySummaries();

        // Exactly one fetch for the whole sweep, and no per-zone fetch at all - asking zone by zone is
        // the ~600-calls-an-hour shape this job deliberately does not have any more.
        verify(profileServiceClient, times(1)).getAllUsersForDailySummary();
        verify(profileServiceClient, never()).getUsersForDailySummary(anyString());
        verify(notificationProviderService, times(1))
                .dispatchEmail(eq("summary@example.com"), anyString(), anyString());
    }

    // The cron fires every hour, so a user whose chosen hour has passed has to be left alone -
    // otherwise they would receive the same summary on all 24 runs of the day.
    @Test
    @DisplayName("Block 7b: The same user one hour past their chosen hour is not mailed - [MEANT TO PASS]")
    void processDailySummaries_userOneHourPastTheirChosenHour_dispatchesNothing() {
        given(clock.instant()).willReturn(NINE_AM_NEW_YORK);
        given(profileServiceClient.getAllUsersForDailySummary())
                .willReturn(List.of(new UserPreferenceResponse(100L, new BigDecimal("100.00"), true, 8, NEW_YORK_ZONE, "summary@example.com")));

        dailyBalanceSummaryJob.processDailySummaries();

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
        // Nobody was due, so the batch balance call must not have happened either.
        verify(accountServiceClient, never()).getAggregateBalancesBatch(any());
    }

    /**
     * Two users in the same timezone with different chosen hours. One global summary hour could not
     * express this at all — whichever hour was configured, one of these two was always mailed at the
     * wrong time.
     *
     * <p>The two runs are order-dependent by design: the mocks are not reset between them, so the
     * closing {@code times(1)} on the 8 o'clock address proves the second run did not mail that user
     * a second time, which a fresh mock could not show.
     */
    @Test
    @DisplayName("Block 7c: Two users in one timezone are each mailed at their own chosen hour - [MEANT TO PASS]")
    void processDailySummaries_twoUsersOneZoneDifferentHours_mailsEachAtTheirOwnHour() {
        UserPreferenceResponse eightOClockUser =
                new UserPreferenceResponse(300L, new BigDecimal("100.00"), true, 8, NEW_YORK_ZONE, "eight@example.com");
        UserPreferenceResponse nineOClockUser =
                new UserPreferenceResponse(301L, new BigDecimal("100.00"), true, 9, NEW_YORK_ZONE, "nine@example.com");

        given(profileServiceClient.getAllUsersForDailySummary())
                .willReturn(List.of(eightOClockUser, nineOClockUser));
        given(accountServiceClient.getAggregateBalancesBatch(eq(List.of(300L))))
                .willReturn(List.of(new UserAggregateBalanceResponse(300L, new BigDecimal("11.00"))));
        given(accountServiceClient.getAggregateBalancesBatch(eq(List.of(301L))))
                .willReturn(List.of(new UserAggregateBalanceResponse(301L, new BigDecimal("22.00"))));

        // 08:00 in New York: the 8 o'clock user only.
        dailyBalanceSummaryJob.processDailySummaries();

        verify(notificationProviderService, times(1))
                .dispatchEmail(eq("eight@example.com"), anyString(), anyString());
        verify(notificationProviderService, never()).dispatchEmail(eq("nine@example.com"), anyString(), anyString());

        // 09:00 in New York, the next run of the same hourly cron.
        given(clock.instant()).willReturn(NINE_AM_NEW_YORK);
        dailyBalanceSummaryJob.processDailySummaries();

        verify(notificationProviderService, times(1))
                .dispatchEmail(eq("nine@example.com"), anyString(), anyString());
        verify(notificationProviderService, times(1))
                .dispatchEmail(eq("eight@example.com"), anyString(), anyString());
    }

    // profile-service already filters opted-out users out, so this asserts the job's own
    // belt-and-braces check - mailing someone who explicitly opted out is the one failure here a
    // customer would complain about.
    @Test
    @DisplayName("Block 7d: A user with dailySummaryEnabled false is never mailed - [MEANT TO PASS]")
    void processDailySummaries_userOptedOutButDueThisHour_dispatchesNothing() {
        given(profileServiceClient.getAllUsersForDailySummary())
                .willReturn(List.of(new UserPreferenceResponse(400L, new BigDecimal("100.00"), false, 8, NEW_YORK_ZONE, "optedout@example.com")));

        dailyBalanceSummaryJob.processDailySummaries();

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
        verify(accountServiceClient, never()).getAggregateBalancesBatch(any());
    }

    @Test
    @DisplayName("Block 7e: A chosen hour still matches local time across a DST switch - [MEANT TO PASS]")
    void processDailySummaries_chosenHourDuringDaylightSaving_stillMatchesLocalTime() {
        // The summer instant is the whole point: matching the hour against a fixed offset rather
        // than resolving it through the zone's rules delivers this one an hour out, twice a year.
        given(clock.instant()).willReturn(EIGHT_AM_NEW_YORK_SUMMER);
        given(profileServiceClient.getAllUsersForDailySummary())
                .willReturn(List.of(new UserPreferenceResponse(500L, new BigDecimal("100.00"), true, 8, NEW_YORK_ZONE, "summer@example.com")));
        given(accountServiceClient.getAggregateBalancesBatch(eq(List.of(500L))))
                .willReturn(List.of(new UserAggregateBalanceResponse(500L, new BigDecimal("33.00"))));

        dailyBalanceSummaryJob.processDailySummaries();

        verify(notificationProviderService, times(1))
                .dispatchEmail(eq("summer@example.com"), anyString(), anyString());
    }

    // One unusable row cannot cost everyone else their summary: the sweep has to carry on to the
    // perfectly good user sitting behind the bad ones in the same list.
    @Test
    @DisplayName("Block 7f: Rows with a null zone, an unknown zone or a null hour are skipped and the sweep completes - [MEANT TO PASS]")
    void processDailySummaries_rowsWithUnusableZoneOrHour_skipsThemAndMailsTheValidUser() {
        given(profileServiceClient.getAllUsersForDailySummary()).willReturn(List.of(
                new UserPreferenceResponse(600L, new BigDecimal("100.00"), true, 8, null, "nozone@example.com"),
                new UserPreferenceResponse(601L, new BigDecimal("100.00"), true, 8, "Mars/Olympus_Mons", "badzone@example.com"),
                // A null hour is the third bad shape: a row written before profile-service had the column.
                new UserPreferenceResponse(602L, new BigDecimal("100.00"), true, null, NEW_YORK_ZONE, "nohour@example.com"),
                new UserPreferenceResponse(603L, new BigDecimal("100.00"), true, 8, NEW_YORK_ZONE, "good@example.com")));
        given(accountServiceClient.getAggregateBalancesBatch(eq(List.of(603L))))
                .willReturn(List.of(new UserAggregateBalanceResponse(603L, new BigDecimal("44.00"))));

        assertThatCode(() -> dailyBalanceSummaryJob.processDailySummaries()).doesNotThrowAnyException();

        verify(notificationProviderService, times(1))
                .dispatchEmail(eq("good@example.com"), anyString(), anyString());
        // The any()-matcher count is what rules out a send to a wrong address: the three bad rows
        // produced no send at all, not a send that merely went somewhere else.
        verify(notificationProviderService, times(1)).dispatchEmail(any(), any(), any());
    }

    @Test
    @DisplayName("Block 8: No opted-in users means no downstream balance lookup or email - [MEANT TO PASS]")
    void processDailySummaries_noUsersReturned_makesNoBalanceLookupAndDispatchesNothing() {
        given(profileServiceClient.getAllUsersForDailySummary()).willReturn(List.of());

        dailyBalanceSummaryJob.processDailySummaries();

        verify(accountServiceClient, never()).getAggregateBalancesBatch(any());
        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
    }

    @Test
    @DisplayName("Block 9: A user with no matching balance entry is skipped, not errored - [MEANT TO PASS]")
    void processDailySummaries_noBalanceReturnedForDueUser_skipsThemWithoutThrowing() {
        given(profileServiceClient.getAllUsersForDailySummary())
                .willReturn(List.of(new UserPreferenceResponse(200L, new BigDecimal("100.00"), true, 8, NEW_YORK_ZONE, "summary@example.com")));
        // Preferences and balances are joined in memory, so a user the account service knows nothing
        // about is a gap in that join rather than an error.
        given(accountServiceClient.getAggregateBalancesBatch(eq(List.of(200L))))
                .willReturn(List.of());

        assertThatCode(() -> dailyBalanceSummaryJob.processDailySummaries()).doesNotThrowAnyException();

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
    }

    // A profile-service outage must not escape into the scheduler thread, where the only symptom
    // would be a cron that quietly stopped firing.
    @Test
    @DisplayName("Final Block: A failure fetching the opted-in users is contained and never reaches the scheduler - [MEANT TO PASS]")
    void processDailySummaries_userFetchThrows_containsTheFailureAndDispatchesNothing() {
        given(profileServiceClient.getAllUsersForDailySummary())
                .willThrow(new RuntimeException("Profile Service unavailable"));

        assertThatCode(() -> dailyBalanceSummaryJob.processDailySummaries()).doesNotThrowAnyException();

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
    }

    // The daily summary used to be the one dispatcher that left no durable trace, so a summary that
    // went out was invisible in GET /api/v1/notifications while every Kafka-driven alert showed up.
    @Test
    @DisplayName("Block 10: A dispatched daily summary is recorded as a SENT DAILY_SUMMARY notification - [MEANT TO PASS]")
    void processDailySummaries_dispatchSucceeds_savesSentDailySummaryEmailRecord() {
        given(profileServiceClient.getAllUsersForDailySummary())
                .willReturn(List.of(new UserPreferenceResponse(100L, new BigDecimal("100.00"), true, 8, NEW_YORK_ZONE, "summary@example.com")));
        given(accountServiceClient.getAggregateBalancesBatch(eq(List.of(100L))))
                .willReturn(List.of(new UserAggregateBalanceResponse(100L, new BigDecimal("5432.10"))));
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);

        dailyBalanceSummaryJob.processDailySummaries();

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRecordRepository, times(1)).save(captor.capture());

        NotificationRecord saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(100L);
        assertThat(saved.getType()).isEqualTo(NotificationType.DAILY_SUMMARY);
        assertThat(saved.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(saved.getStatus()).isEqualTo(NotificationStatus.SENT);
        // The balance has to survive into the stored body, otherwise the feed shows an empty shell.
        assertThat(saved.getMessage()).contains("5432.10");
    }

    // dispatchEmail returns false once @Recover has swallowed the exception and exhausted the
    // retries; that boolean is the only signal the send actually failed, so the record must follow it.
    @Test
    @DisplayName("Block 11: A failed summary dispatch is recorded as FAILED, not SENT - [MEANT TO PASS]")
    void processDailySummaries_dispatchReturnsFalse_savesRecordWithFailedStatus() {
        given(profileServiceClient.getAllUsersForDailySummary())
                .willReturn(List.of(new UserPreferenceResponse(101L, new BigDecimal("100.00"), true, 8, NEW_YORK_ZONE, "summary@example.com")));
        given(accountServiceClient.getAggregateBalancesBatch(eq(List.of(101L))))
                .willReturn(List.of(new UserAggregateBalanceResponse(101L, new BigDecimal("10.00"))));
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(false);

        dailyBalanceSummaryJob.processDailySummaries();

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRecordRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    @DisplayName("Block 12: A user with no email on file is recorded as FAILED and never dispatched - [MEANT TO PASS]")
    void processDailySummaries_dueUserHasNoEmailOnFile_savesFailedRecordAndDispatchesNothing() {
        // A null email is a real state, not a defensive hypothetical: users who registered before the
        // email field existed have none, so no send is possible - but the miss is still recorded.
        given(profileServiceClient.getAllUsersForDailySummary())
                .willReturn(List.of(new UserPreferenceResponse(102L, new BigDecimal("100.00"), true, 8, NEW_YORK_ZONE, null)));
        given(accountServiceClient.getAggregateBalancesBatch(eq(List.of(102L))))
                .willReturn(List.of(new UserAggregateBalanceResponse(102L, new BigDecimal("77.00"))));

        dailyBalanceSummaryJob.processDailySummaries();

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRecordRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(102L);
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    /**
     * The manual trigger path {@code InternalNotificationController} exposes. With the global summary
     * hour retired, this single-zone form is the only way to bring an end-to-end email check forward
     * to the current moment, so it must run whatever the clock says.
     */
    @Test
    @DisplayName("Block 13: Running a single timezone directly bypasses the hour check - [MEANT TO PASS]")
    void processUsersForTimezone_userNotDueByTheClock_stillDispatchesAndRecords() {
        String offHourZone = "Asia/Tokyo";
        // Chosen hour 3, and the stubbed clock is 08:00 New York, which is 22:00 in Tokyo - the
        // scheduled sweep would pass this user over twice, on the zone and on the hour.
        given(profileServiceClient.getUsersForDailySummary(eq(offHourZone)))
                .willReturn(List.of(new UserPreferenceResponse(103L, new BigDecimal("100.00"), true, 3, offHourZone, "tokyo@example.com")));
        given(accountServiceClient.getAggregateBalancesBatch(eq(List.of(103L))))
                .willReturn(List.of(new UserAggregateBalanceResponse(103L, new BigDecimal("900.00"))));
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);

        dailyBalanceSummaryJob.processUsersForTimezone(offHourZone);

        verify(notificationProviderService, times(1))
                .dispatchEmail(eq("tokyo@example.com"), anyString(), anyString());
        verify(notificationRecordRepository, times(1)).save(any(NotificationRecord.class));
        // It did NOT fall back to the all-users fetch - the single-zone form is still a single zone's
        // worth of work.
        verify(profileServiceClient, never()).getAllUsersForDailySummary();
    }

    @Test
    @DisplayName("Block 14: A 2FA request emails the code to the address on the event - [MEANT TO PASS]")
    void consumeTwoFactorRequest_eventCarriesEmailAndCode_emailsTheCodeToThatAddress() {
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);

        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEvent("user@example.com", "123456"));

        verify(notificationProviderService, times(1))
                .dispatchEmail(eq("user@example.com"), anyString(), contains("123456"));
        // No profile lookup happens on this path at all - auth-service already has the address in
        // hand when it generates the code, which is what keeps a login off profile-service's uptime.
        verify(profileServiceClient, never()).getUserPreferences(any());
    }

    // The point of the SMS-to-email migration. The payload still carries a phoneNumber, so a listener
    // reading it would silently keep the old paid channel alive alongside the new one.
    @Test
    @DisplayName("Block 14b: A 2FA request sends no SMS even though the event carries a number - [MEANT TO PASS]")
    void consumeTwoFactorRequest_eventStillCarriesPhoneNumber_sendsNoSms() {
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);

        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEvent("user@example.com", "123456"));

        verify(notificationProviderService, never()).dispatchSms(any(), any());
    }

    // No address on file is a login that cannot complete, not just a missed alert - so the miss is
    // recorded rather than passed over in silence, the same way DailyBalanceSummaryJob handles it.
    @Test
    @DisplayName("Block 14c: A 2FA request with no email dispatches nothing and records FAILED - [MEANT TO PASS]")
    void consumeTwoFactorRequest_eventWithoutEmail_dispatchesNothingAndSavesFailedRecord() {
        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEvent(null, "123456"));

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
        verify(notificationProviderService, never()).dispatchSms(any(), any());

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRecordRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.EMAIL_2FA);
        assertThat(captor.getValue().getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    // The sentence in the email and the countdown on the login screen have to agree. The body used to
    // say a hardcoded "5 minutes" while the code died at 3:00, so a user who trusted the email was
    // told they had two minutes that did not exist and got EXPIRED on a code typed in good faith.
    @Test
    @DisplayName("Block 14d: The email states the expiry carried on the event - [MEANT TO PASS]")
    void consumeTwoFactorRequest_expiryOf180Seconds_bodyStatesThreeMinutes() {
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);

        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEvent("user@example.com", "123456"));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationProviderService).dispatchEmail(anyString(), anyString(), body.capture());
        assertThat(body.getValue()).contains("It expires in 3 minutes.");
        // The literal that used to be there - if it survives anywhere in the body the email is lying.
        assertThat(body.getValue()).doesNotContain("5 minutes");
    }

    // 90 seconds is the shortest value that exposes the bug: a TTL that is not a whole number of
    // minutes is one config change away, and integer division alone renders it as "1 minute",
    // telling the user their code dies half a minute before it does.
    @Test
    @DisplayName("Block 14e: A part-minute expiry reads correctly rather than being truncated - [MEANT TO PASS]")
    void consumeTwoFactorRequest_expiryOf90Seconds_bodyStatesOneMinuteThirtySeconds() {
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);
        Map<String, Object> event = twoFactorEvent("user@example.com", "123456");
        event.put("expiresInSeconds", "90");

        twoFactorEmailListener.consumeTwoFactorRequest(event);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationProviderService).dispatchEmail(anyString(), anyString(), body.capture());
        // Singular unit spelled correctly - "1 minutes" in a real security email reads as a phish.
        assertThat(body.getValue()).contains("It expires in 1 minute 30 seconds.");
    }

    // auth-service and this service deploy independently, so a code can arrive from a producer that
    // has never heard of expiresInSeconds. An exception here would drop the code and strand the login.
    @Test
    @DisplayName("Block 14f: An event with no expiresInSeconds falls back to 3 minutes - [MEANT TO PASS]")
    void consumeTwoFactorRequest_expiryKeyAbsent_stillSendsAndFallsBackToThreeMinutes() {
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);
        Map<String, Object> event = twoFactorEvent("user@example.com", "123456");
        event.remove("expiresInSeconds");

        twoFactorEmailListener.consumeTwoFactorRequest(event);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationProviderService).dispatchEmail(anyString(), anyString(), body.capture());
        assertThat(body.getValue()).contains("123456");
        assertThat(body.getValue()).contains("It expires in 3 minutes.");
    }

    // Garbage in the key is the other half of the same rollout risk: a non-numeric string must not
    // take the listener down the catch block and lose the send.
    @Test
    @DisplayName("Block 14g: An unparseable expiresInSeconds still sends the code - [MEANT TO PASS]")
    void consumeTwoFactorRequest_expiryValueUnparseable_stillSendsAndFallsBackToThreeMinutes() {
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);
        Map<String, Object> event = twoFactorEvent("user@example.com", "123456");
        event.put("expiresInSeconds", "soon");

        twoFactorEmailListener.consumeTwoFactorRequest(event);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationProviderService, times(1))
                .dispatchEmail(anyString(), anyString(), body.capture());
        assertThat(body.getValue()).contains("It expires in 3 minutes.");
    }

    // The exact envelope auth-service publishes on notification-events, including the TTL the login
    // screen counts down. A HashMap rather than Map.of because a null email is a real state - a user
    // who registered before the field existed - and because these tests override single keys.
    private Map<String, Object> twoFactorEvent(String email, String code) {
        Map<String, Object> event = new HashMap<>();
        event.put("action", "TWO_FA_REQUESTED");
        event.put("userId", "42");
        event.put("email", email);
        event.put("phoneNumber", "+15551234567");
        event.put("code", code);
        event.put("expiresInSeconds", "180");
        return event;
    }
}
