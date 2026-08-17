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

    // Mocked rather than hitting the real table: these tests assert on WHAT gets recorded, and a
    // mock makes that a direct verify instead of a save-then-query round trip against a database
    // this suite otherwise never needs.
    @MockBean
    private NotificationRecordRepository notificationRecordRepository;

    @MockBean
    private Clock clock;

    // 2024-01-15T13:00:00Z is 08:00 local time in America/New_York (EST, UTC-5, no DST in
    // January) - a fixed instant so DailyBalanceSummaryJob's per-user local-hour matching is
    // deterministic across test runs, instead of depending on the real wall clock.
    private static final Instant EIGHT_AM_NEW_YORK = Instant.parse("2024-01-15T13:00:00Z");
    // The same wall clock one hour on, 09:00 in New York. Used to prove a user whose chosen hour is 8
    // is passed over on the very next run of an hourly cron.
    private static final Instant NINE_AM_NEW_YORK = Instant.parse("2024-01-15T14:00:00Z");
    // July, when New York is on EDT (UTC-4), so 08:00 local is a different instant than it is in
    // January. A job matching on a stored offset instead of a ZonedDateTime gets this one wrong.
    private static final Instant EIGHT_AM_NEW_YORK_SUMMER = Instant.parse("2024-07-15T12:00:00Z");
    private static final String NEW_YORK_ZONE = "America/New_York";

    // runs before every test, pins the mocked clock to a fixed instant, 8am new york time in january
    // this makes dailybalancesummaryjob's local-hour matching deterministic instead of depending on
    // whatever the real wall clock happens to be when the test suite runs
    @BeforeEach
    void setUpClock() {
        given(clock.instant()).willReturn(EIGHT_AM_NEW_YORK);
    }

    // checking that a transfer at or above the user's alert threshold actually triggers an email
    // build a fundstransferredevent, using userId 42 but deliberately different account ids, 501 and 502,
    // so this test cannot pass by accident if the listener code regresses to reading an account id instead
    // stub the user's preferences with a hundred dollar threshold, well below the 150 dollar transfer
    // call consumetransferevent directly like the kafka listener would
    // verify an email got dispatched to this specific user's address
    // and verify the listener never mistakenly looked up preferences using the account id instead
    @Test
    @DisplayName("Block 1: Transaction at/above the user's threshold dispatches an alert - [MEANT TO PASS]")
    void testBlock1_transferAtOrAboveThreshold_dispatchesAlert() {
        // userId (42L) is deliberately distinct from fromAccountId/toAccountId (501L/502L) so this
        // test cannot pass by accident if the listener regresses to using an account ID again.
        FundsTransferredEvent event = new FundsTransferredEvent(42L, 501L, 502L, new BigDecimal("150.00"), UUID.randomUUID());
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new UserPreferenceResponse(42L, new BigDecimal("100.00"), true, 8, "America/New_York", "alerts@example.com"));

        transactionAlertListener.consumeTransferEvent(event);

        verify(notificationProviderService).dispatchEmail(eq("alerts@example.com"), anyString(), anyString());
        verify(profileServiceClient, never()).getUserPreferences(501L);
    }

    // the flip side of the last test, a small transfer that should stay under the radar
    // build an event for a fifty dollar transfer, below the stubbed hundred dollar threshold
    // call consumetransferevent directly
    // verify dispatchemail never got called at all, since the amount never crossed the threshold
    @Test
    @DisplayName("Block 2: Transaction below the user's threshold does not dispatch an alert - [MEANT TO PASS]")
    void testBlock2_transferBelowThreshold_noAlert() {
        FundsTransferredEvent event = new FundsTransferredEvent(42L, 501L, 502L, new BigDecimal("50.00"), UUID.randomUUID());
        given(profileServiceClient.getUserPreferences(42L))
                .willReturn(new UserPreferenceResponse(42L, new BigDecimal("100.00"), true, 8, "America/New_York", "alerts@example.com"));

        transactionAlertListener.consumeTransferEvent(event);

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
    }

    // defensive check for when the profile service has no preferences on file for this user at all
    // stub getuserpreferences to return null, like a user who never set up alert preferences
    // call consumetransferevent and expect it to not throw any exception
    // then verify no email attempt was made either, since there is nothing to base a threshold check on
    @Test
    @DisplayName("Block 3: Missing preferences skips the alert without throwing - [MEANT TO PASS]")
    void testBlock3_missingPreferences_skipsAlertGracefully() {
        FundsTransferredEvent event = new FundsTransferredEvent(42L, 501L, 502L, new BigDecimal("500.00"), UUID.randomUUID());
        given(profileServiceClient.getUserPreferences(42L)).willReturn(null);

        assertThatCode(() -> transactionAlertListener.consumeTransferEvent(event)).doesNotThrowAnyException();

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
    }

    // making sure a downstream outage in the profile service does not take down the kafka consumer thread
    // stub getuserpreferences to throw a runtime exception, simulating profile service being unreachable
    // call consumetransferevent and expect it to not throw anything back out
    // then verify no email attempt was made, since we could not even check the threshold in the first place
    // a single bad event or a temporary outage should never kill the whole consumer loop
    @Test
    @DisplayName("Block 4: Profile Service failure is swallowed so the Kafka consumer thread survives - [MEANT TO PASS]")
    void testBlock4_profileServiceFailure_doesNotCrashListener() {
        FundsTransferredEvent event = new FundsTransferredEvent(42L, 501L, 502L, new BigDecimal("500.00"), UUID.randomUUID());
        given(profileServiceClient.getUserPreferences(42L)).willThrow(new RuntimeException("Profile Service unavailable"));

        assertThatCode(() -> transactionAlertListener.consumeTransferEvent(event)).doesNotThrowAnyException();

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
    }

    // regression test making sure the listener uses the real userId field, not one of the account ids
    // build an event with userId 777 and two very different looking account ids, 111 and 222
    // stub preferences only for user 777, leaving 111 and 222 completely unstubbed on purpose
    // call consumetransferevent
    // verify preferences got looked up for 777 specifically, and never for either account id
    // then confirm the email still went out correctly to that user's address
    @Test
    @DisplayName("Block 5: Listener queries preferences by the event's userId, never by an account ID - [MEANT TO PASS]")
    void testBlock5_listenerUsesUserIdNotAccountId() {
        FundsTransferredEvent event = new FundsTransferredEvent(777L, 111L, 222L, new BigDecimal("200.00"), UUID.randomUUID());
        given(profileServiceClient.getUserPreferences(777L))
                .willReturn(new UserPreferenceResponse(777L, new BigDecimal("100.00"), true, 8, "UTC", "alerts@example.com"));

        transactionAlertListener.consumeTransferEvent(event);

        verify(profileServiceClient).getUserPreferences(777L);
        verify(profileServiceClient, never()).getUserPreferences(111L);
        verify(profileServiceClient, never()).getUserPreferences(222L);
        verify(notificationProviderService).dispatchEmail(eq("alerts@example.com"), anyString(), anyString());
    }

    // USER STORY 9.3 (NotificationProviderService's own dispatch/retry/recover behavior) is
    // covered by the dedicated NotificationProviderServiceTestSuite, not here - this suite
    // @MockBeans NotificationProviderService to verify its callers, which would conflict with
    // exercising the real AOP-proxied bean's retry logic in the same Spring context.

    // checking the scheduled daily summary job actually emails users who are opted in and have a balance
    // thanks to the clock stub in setUpClock this always looks like 8am in america/new_york,
    // so a user whose chosen hour is 8 and whose timezone is new york is due right now
    // stub the profile service to return that one opted in user, no timezone filter involved
    // stub the account service to return an aggregate balance for that same user
    // call processdailysummaries directly, the same way the scheduler would trigger it
    // verify the opted-in fetch happened exactly once, and that one summary email actually went out
    @Test
    @DisplayName("Block 7: Opted-in users with a matching balance receive a summary email - [MEANT TO PASS]")
    void testBlock7_optedInUsersWithBalance_receiveSummaryEmail() {
        // DailyBalanceSummaryJob reads Instant.now(clock) instead of the real wall clock, so with the
        // fixed 08:00 America/New_York instant stubbed in @BeforeEach this user's chosen hour of 8 is
        // the hour it currently is where they live.
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

    // The other half of Block 7. The cron fires every hour, so a user whose chosen hour has passed has
    // to be left alone - otherwise they would receive the same summary on all 24 runs of the day.
    @Test
    @DisplayName("Block 7b: The same user one hour past their chosen hour is not mailed - [MEANT TO PASS]")
    void testBlock7b_userPastTheirChosenHour_isNotMailed() {
        given(clock.instant()).willReturn(NINE_AM_NEW_YORK);
        given(profileServiceClient.getAllUsersForDailySummary())
                .willReturn(List.of(new UserPreferenceResponse(100L, new BigDecimal("100.00"), true, 8, NEW_YORK_ZONE, "summary@example.com")));

        dailyBalanceSummaryJob.processDailySummaries();

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
        // Nobody was due, so the batch balance call must not have happened either.
        verify(accountServiceClient, never()).getAggregateBalancesBatch(any());
    }

    // THE test for this feature. Two users in the SAME timezone with DIFFERENT chosen hours: at 08:00
    // New York only the 8 o'clock user is mailed, and at 09:00 only the 9 o'clock user is. One global
    // summary hour could not express this at all - whichever hour was configured, one of these two was
    // always mailed at the wrong time.
    @Test
    @DisplayName("Block 7c: Two users in one timezone are each mailed at their own chosen hour - [MEANT TO PASS]")
    void testBlock7c_sameTimezoneDifferentHours_eachMailedAtTheirOwn() {
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

        // 09:00 in New York, the next run of the same hourly cron: now the 9 o'clock user, and the
        // 8 o'clock user is not mailed a second time.
        given(clock.instant()).willReturn(NINE_AM_NEW_YORK);
        dailyBalanceSummaryJob.processDailySummaries();

        verify(notificationProviderService, times(1))
                .dispatchEmail(eq("nine@example.com"), anyString(), anyString());
        verify(notificationProviderService, times(1))
                .dispatchEmail(eq("eight@example.com"), anyString(), anyString());
    }

    // A user who turned summaries off is never mailed, even when their stored hour is the hour it is.
    // profile-service already filters these out, so this asserts the job's own belt-and-braces check -
    // mailing someone who explicitly opted out is the one failure here a customer would complain about.
    @Test
    @DisplayName("Block 7d: A user with dailySummaryEnabled false is never mailed - [MEANT TO PASS]")
    void testBlock7d_optedOutUser_isNeverMailed() {
        given(profileServiceClient.getAllUsersForDailySummary())
                .willReturn(List.of(new UserPreferenceResponse(400L, new BigDecimal("100.00"), false, 8, NEW_YORK_ZONE, "optedout@example.com")));

        dailyBalanceSummaryJob.processDailySummaries();

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
        verify(accountServiceClient, never()).getAggregateBalancesBatch(any());
    }

    // "08:00 local" has to still mean 08:00 local in July. New York is UTC-5 in winter and UTC-4 in
    // summer, so matching the hour against a fixed offset instead of resolving it through the zone's
    // rules would deliver this one an hour out - twice a year, for half the world.
    @Test
    @DisplayName("Block 7e: A chosen hour still matches local time across a DST switch - [MEANT TO PASS]")
    void testBlock7e_chosenHourSurvivesDaylightSaving() {
        given(clock.instant()).willReturn(EIGHT_AM_NEW_YORK_SUMMER);
        given(profileServiceClient.getAllUsersForDailySummary())
                .willReturn(List.of(new UserPreferenceResponse(500L, new BigDecimal("100.00"), true, 8, NEW_YORK_ZONE, "summer@example.com")));
        given(accountServiceClient.getAggregateBalancesBatch(eq(List.of(500L))))
                .willReturn(List.of(new UserAggregateBalanceResponse(500L, new BigDecimal("33.00"))));

        dailyBalanceSummaryJob.processDailySummaries();

        verify(notificationProviderService, times(1))
                .dispatchEmail(eq("summer@example.com"), anyString(), anyString());
    }

    // One unusable row cannot cost everyone else their summary. A null timezone, a string that isn't a
    // zone id, and a null hour are all skippable data problems for one user; the sweep has to carry on
    // to the perfectly good user sitting behind them in the same list.
    @Test
    @DisplayName("Block 7f: Users with a null or garbage timezone are skipped and the sweep completes - [MEANT TO PASS]")
    void testBlock7f_badTimezoneRows_areSkippedAndTheSweepContinues() {
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
        // and nobody else - the three bad rows produced no send at all, not a send to a wrong address
        verify(notificationProviderService, times(1)).dispatchEmail(any(), any(), any());
    }

    // making sure an empty opted in list short circuits instead of doing pointless downstream work
    // stub the profile service to return an empty list of opted in users
    // call processdailysummaries
    // verify the account service balance lookup never got called at all
    // and verify no email attempt was made either, since there was nobody to send one to
    @Test
    @DisplayName("Block 8: No opted-in users means no downstream balance lookup or email - [MEANT TO PASS]")
    void testBlock8_noOptedInUsers_noDownstreamCalls() {
        given(profileServiceClient.getAllUsersForDailySummary()).willReturn(List.of());

        dailyBalanceSummaryJob.processDailySummaries();

        verify(accountServiceClient, never()).getAggregateBalancesBatch(any());
        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
    }

    // edge case where the profile service knows about a user but the account service has no balance for them
    // stub the profile service to return one opted in user who is due right now
    // stub the account service's batch balance lookup to come back completely empty for that user
    // call processdailysummaries and expect no exception, this is an in memory join that has to handle gaps
    // then verify no email attempt was made for a user with no balance data to actually report
    @Test
    @DisplayName("Block 9: A user with no matching balance entry is skipped, not errored - [MEANT TO PASS]")
    void testBlock9_userWithoutMatchingBalance_isSkipped() {
        given(profileServiceClient.getAllUsersForDailySummary())
                .willReturn(List.of(new UserPreferenceResponse(200L, new BigDecimal("100.00"), true, 8, NEW_YORK_ZONE, "summary@example.com")));
        given(accountServiceClient.getAggregateBalancesBatch(eq(List.of(200L))))
                .willReturn(List.of()); // Account Service returned nothing for this user

        assertThatCode(() -> dailyBalanceSummaryJob.processDailySummaries()).doesNotThrowAnyException();

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
    }

    // making sure a profile-service outage cannot escape into the scheduler thread, where the only
    // symptom would be a cron that stopped firing
    // stub the opted-in fetch so it just throws
    // call processdailysummaries and expect it to not throw anything back out
    // then verify no email attempt was made, since nothing could be looked up in this failure scenario
    @Test
    @DisplayName("Final Block: A failure fetching the opted-in users does not abort the sweep - [MEANT TO PASS]")
    void testFinalAC_userFetchFailure_isolatedAndDoesNotPropagate() {
        given(profileServiceClient.getAllUsersForDailySummary())
                .willThrow(new RuntimeException("Profile Service unavailable"));

        assertThatCode(() -> dailyBalanceSummaryJob.processDailySummaries()).doesNotThrowAnyException();

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
    }

    // the daily summary used to be the one dispatcher that left no durable trace, so a summary that
    // went out was invisible in GET /api/v1/notifications while every kafka-driven alert showed up
    // stub a successful dispatch and capture what the job saved
    // verify the record is typed DAILY_SUMMARY on the EMAIL channel and marked SENT
    @Test
    @DisplayName("Block 10: A dispatched daily summary is recorded as a SENT DAILY_SUMMARY notification - [MEANT TO PASS]")
    void testBlock10_dispatchedSummary_isRecordedAsSent() {
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
        // the balance has to survive into the stored body, otherwise the feed shows an empty shell
        assertThat(saved.getMessage()).contains("5432.10");
    }

    // dispatchEmail returns false once @Recover has swallowed the exception and exhausted the retries,
    // that boolean is the only signal the send actually failed, so the record has to follow it
    @Test
    @DisplayName("Block 11: A failed summary dispatch is recorded as FAILED, not SENT - [MEANT TO PASS]")
    void testBlock11_failedSummaryDispatch_isRecordedAsFailed() {
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

    // a user opted into summaries but with no email on file is a real state, users registered before
    // the email field existed have none, no send is possible so nothing is dispatched, but the miss
    // is still recorded rather than passed over in silence
    @Test
    @DisplayName("Block 12: A user with no email on file is recorded as FAILED and never dispatched - [MEANT TO PASS]")
    void testBlock12_userWithoutEmail_isRecordedFailedAndNotDispatched() {
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

    // the manual trigger path InternalNotificationController uses, running one zone directly without
    // consulting the clock at all - this is what makes an end-to-end email check possible at any hour,
    // and with the global summary hour retired it is the ONLY thing that can bring that moment forward
    @Test
    @DisplayName("Block 13: Running a single timezone directly bypasses the hour check - [MEANT TO PASS]")
    void testBlock13_processUsersForTimezone_runsRegardlessOfHour() {
        String offHourZone = "Asia/Tokyo";
        // Chosen hour 3, and it is 22:00 in Tokyo - this user is not due by any reading of the clock.
        given(profileServiceClient.getUsersForDailySummary(eq(offHourZone)))
                .willReturn(List.of(new UserPreferenceResponse(103L, new BigDecimal("100.00"), true, 3, offHourZone, "tokyo@example.com")));
        given(accountServiceClient.getAggregateBalancesBatch(eq(List.of(103L))))
                .willReturn(List.of(new UserAggregateBalanceResponse(103L, new BigDecimal("900.00"))));
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);

        // The stubbed clock is 08:00 in New York, which is 22:00 in Tokyo - so the scheduled sweep
        // would pass this user over twice, on the zone and on the hour. Calling it directly still sends.
        dailyBalanceSummaryJob.processUsersForTimezone(offHourZone);

        verify(notificationProviderService, times(1))
                .dispatchEmail(eq("tokyo@example.com"), anyString(), anyString());
        verify(notificationRecordRepository, times(1)).save(any(NotificationRecord.class));
        // and it did NOT fall back to the all-users fetch - the single-zone form is still a single
        // zone's worth of work
        verify(profileServiceClient, never()).getAllUsersForDailySummary();
    }

    // the 2FA code itself is a notification like any other now that it goes by email, so it belongs in
    // the same dispatch suite as the alerts and summaries - NotificationPersistenceTestSuite covers
    // what gets RECORDED, this covers what gets SENT
    // the address comes straight off the event, not from a profile lookup, because auth-service
    // already has it in hand at the moment it generates the code
    @Test
    @DisplayName("Block 14: A 2FA request emails the code to the address on the event - [MEANT TO PASS]")
    void testBlock14_twoFactorRequest_emailsTheCode() {
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);

        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEvent("user@example.com", "123456"));

        verify(notificationProviderService, times(1))
                .dispatchEmail(eq("user@example.com"), anyString(), contains("123456"));
        // the address on the event is the one used - no profile lookup happens on this path at all,
        // which is what keeps a login from depending on profile-service being up
        verify(profileServiceClient, never()).getUserPreferences(any());
    }

    // the point of the migration: no SMS goes out for a 2FA code any more, whatever the event still
    // carries. the payload keeps a phoneNumber, so a listener reading it would silently keep the old
    // paid channel alive alongside the new one
    @Test
    @DisplayName("Block 14b: A 2FA request sends no SMS even though the event carries a number - [MEANT TO PASS]")
    void testBlock14b_twoFactorRequest_sendsNoSms() {
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);

        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEvent("user@example.com", "123456"));

        verify(notificationProviderService, never()).dispatchSms(any(), any());
    }

    // no address on file is a login that cannot complete, not just a missed alert - nothing is
    // dispatched, and the miss is recorded rather than passed over in silence, the same way
    // DailyBalanceSummaryJob handles a user with no email
    @Test
    @DisplayName("Block 14c: A 2FA request with no email dispatches nothing and records FAILED - [MEANT TO PASS]")
    void testBlock14c_twoFactorRequestWithoutEmail_recordsFailed() {
        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEvent(null, "123456"));

        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
        verify(notificationProviderService, never()).dispatchSms(any(), any());

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRecordRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.EMAIL_2FA);
        assertThat(captor.getValue().getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    // the sentence in the email and the countdown on the login screen have to agree. the body used to
    // say a hardcoded "5 minutes" while the code died at 3:00, so a user who trusted the email was
    // told they had two minutes that did not exist and got EXPIRED on a code they typed in good faith
    @Test
    @DisplayName("Block 14d: The email states the expiry carried on the event - [MEANT TO PASS]")
    void testBlock14d_twoFactorRequest_bodyStatesTheEventsExpiry() {
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);

        twoFactorEmailListener.consumeTwoFactorRequest(twoFactorEvent("user@example.com", "123456"));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationProviderService).dispatchEmail(anyString(), anyString(), body.capture());
        assertThat(body.getValue()).contains("It expires in 3 minutes.");
        // the literal that used to be there - if it survives anywhere in the body the email is lying
        assertThat(body.getValue()).doesNotContain("5 minutes");
    }

    // a TTL that is not a whole number of minutes is a config change away, and integer division alone
    // would render 90s as "1 minute" - telling the user their code dies half a minute before it does
    @Test
    @DisplayName("Block 14e: A part-minute expiry reads correctly rather than being truncated - [MEANT TO PASS]")
    void testBlock14e_twoFactorRequest_partMinuteExpiryReadsCorrectly() {
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);

        Map<String, Object> event = twoFactorEvent("user@example.com", "123456");
        event.put("expiresInSeconds", "90");
        twoFactorEmailListener.consumeTwoFactorRequest(event);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationProviderService).dispatchEmail(anyString(), anyString(), body.capture());
        // singular unit spelled correctly - "1 minutes" in a real security email reads as a phish
        assertThat(body.getValue()).contains("It expires in 1 minute 30 seconds.");
    }

    // auth-service and this service deploy independently, so a code can arrive from a producer that
    // has never heard of expiresInSeconds. that must fall back to the current 180s and still send -
    // an exception here would drop the code entirely and strand the login
    @Test
    @DisplayName("Block 14f: An event with no expiresInSeconds falls back to 3 minutes - [MEANT TO PASS]")
    void testBlock14f_twoFactorRequest_missingExpiryFallsBackToDefault() {
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);

        Map<String, Object> event = twoFactorEvent("user@example.com", "123456");
        event.remove("expiresInSeconds");
        twoFactorEmailListener.consumeTwoFactorRequest(event);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationProviderService).dispatchEmail(anyString(), anyString(), body.capture());
        assertThat(body.getValue()).contains("123456");
        assertThat(body.getValue()).contains("It expires in 3 minutes.");
    }

    // garbage in the key is the other half of the same rollout risk - a null or a non-numeric string
    // must not take the listener down the catch block and lose the send
    @Test
    @DisplayName("Block 14g: An unparseable expiresInSeconds still sends the code - [MEANT TO PASS]")
    void testBlock14g_twoFactorRequest_unparseableExpiryStillSends() {
        given(notificationProviderService.dispatchEmail(anyString(), anyString(), anyString())).willReturn(true);

        Map<String, Object> event = twoFactorEvent("user@example.com", "123456");
        event.put("expiresInSeconds", "soon");
        twoFactorEmailListener.consumeTwoFactorRequest(event);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationProviderService, times(1))
                .dispatchEmail(anyString(), anyString(), body.capture());
        assertThat(body.getValue()).contains("It expires in 3 minutes.");
    }

    // the exact envelope auth-service publishes on notification-events, including the TTL the login
    // screen counts down. a HashMap rather than Map.of because a null email is a real state - a user
    // who registered before the field existed - and because these tests override single keys
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
