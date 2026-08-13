package com.example.notificationservice.job;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.notificationservice.client.AccountServiceClient;
import com.example.notificationservice.client.ProfileServiceClient;
import com.example.notificationservice.model.NotificationChannel;
import com.example.notificationservice.model.NotificationRecord;
import com.example.notificationservice.model.NotificationStatus;
import com.example.notificationservice.model.NotificationType;
import com.example.notificationservice.repository.NotificationRecordRepository;
import com.example.notificationservice.service.NotificationProviderService;

@Component
public class DailyBalanceSummaryJob {

    private static final Logger log = LoggerFactory.getLogger(DailyBalanceSummaryJob.class);

    private static final String EMAIL_SUBJECT = "Your Daily Balance Summary";

    private final ProfileServiceClient profileServiceClient;
    private final AccountServiceClient accountServiceClient;
    private final NotificationProviderService notificationProviderService;
    private final NotificationRecordRepository notificationRecordRepository;
    private final Clock clock;

    // injecting a plain java.time.Clock instead of calling Instant.now() directly everywhere,
    // learned this is what actually let the test suite freeze time and stub a fixed 8am instant
    public DailyBalanceSummaryJob(ProfileServiceClient profileServiceClient,
                                  AccountServiceClient accountServiceClient,
                                  NotificationProviderService notificationProviderService,
                                  NotificationRecordRepository notificationRecordRepository,
                                  Clock clock) {
        this.profileServiceClient = profileServiceClient;
        this.accountServiceClient = accountServiceClient;
        this.notificationProviderService = notificationProviderService;
        this.notificationRecordRepository = notificationRecordRepository;
        this.clock = clock;
    }

    // Each user picks their own summary hour, so this sweep fetches the whole opted-in population
    // once and decides user by user whether their local clock currently reads the hour they asked for.
    //
    // The old shape - "find the timezones that are at 08:00, then ask profile-service for the users in
    // each" - only worked because the hour was one global value, which made most of the world
    // irrelevant on any given run. With a per-user hour every zone is potentially a target, so that
    // narrowing narrows nothing, and querying zone by zone would mean roughly 600 HTTP calls an hour
    // to answer a question one call answers.
    //
    // Being honest about the cost of the shape that replaced it: this pulls every opted-in user across
    // the bank into memory once an hour, including the ~23/24 of them who aren't due anything. That is
    // one small round trip at this scale and far cheaper than what it replaced, but it does grow with
    // the customer base rather than with the work actually being done - at a size where the list stops
    // fitting comfortably in memory this wants a server-side filter (profile-service returning only
    // the users whose local hour is now) rather than a bigger heap here.
    @Scheduled(cron = "0 0 * * * *")
    public void processDailySummaries() {
        Instant now = Instant.now(clock);
        log.info("Starting the hourly Daily Balance Summary sweep.");

        List<ProfileServiceClient.UserPreferenceResponse> optedInUsers;
        try {
            optedInUsers = profileServiceClient.getAllUsersForDailySummary();
        } catch (Exception e) {
            // The fetch is the whole sweep now, so there is nothing to isolate a failure from - but it
            // still must not escape into the scheduler thread, which would leave the failure visible
            // only as a dead cron.
            log.error("Could not fetch the opted-in users. Skipping this sweep.", e);
            return;
        }

        if (optedInUsers == null || optedInUsers.isEmpty()) {
            log.info("No users are opted in to the daily summary. Job ending.");
            return;
        }

        List<ProfileServiceClient.UserPreferenceResponse> dueNow = optedInUsers.stream()
                .filter(user -> isDueThisHour(user, now))
                .toList();

        if (dueNow.isEmpty()) {
            log.info("None of the {} opted-in users are due a summary this hour. Job ending.", optedInUsers.size());
            return;
        }

        log.info("{} of {} opted-in users are due a summary this hour. Fetching bulk balances.",
                dueNow.size(), optedInUsers.size());

        Map<Long, AccountServiceClient.UserAggregateBalanceResponse> balanceMap = fetchBalanceMap(dueNow);

        for (ProfileServiceClient.UserPreferenceResponse user : dueNow) {
            try {
                dispatchSummary(user, balanceMap.get(user.userId()));
            } catch (Exception e) {
                // Isolation per user, where the old code isolated per timezone - the unit of work in
                // this loop is now one user, so that is the granularity a failure has to stop at. One
                // undeliverable address cannot cost everybody else their summary.
                log.error("Failed to send the daily summary for user {}", user.userId(), e);
            }
        }
    }

    // Whether this user's chosen hour is the hour it currently is where they live.
    //
    // ZonedDateTime rather than a stored UTC offset: an offset is only correct until the zone's next
    // DST transition, and "08:00 local" has to stay 08:00 local across it. Resolving the instant
    // through the zone's rules on every run is what makes the summary land at the same local time in
    // March as it did in February.
    //
    // Every rejection is a skip with a log line rather than a throw. A single row with a null hour or
    // a timezone string that isn't a zone id is a data problem for one user; letting it out of here
    // would make it an outage for everyone.
    private boolean isDueThisHour(ProfileServiceClient.UserPreferenceResponse user, Instant now) {
        try {
            // profile-service only returns opted-in users, so this is belt and braces - but the flag is
            // right here on the record, and mailing someone who explicitly turned summaries off is the
            // one failure in this job a customer would actually complain about.
            if (!Boolean.TRUE.equals(user.dailySummaryEnabled())) {
                return false;
            }

            Integer chosenHour = user.dailySummaryHour();
            if (chosenHour == null) {
                log.warn("Skipping user {} in the daily summary sweep - no summary hour on file", user.userId());
                return false;
            }

            String timezone = user.timezone();
            if (timezone == null || timezone.isBlank()) {
                log.warn("Skipping user {} in the daily summary sweep - no timezone on file", user.userId());
                return false;
            }

            ZonedDateTime localNow = ZonedDateTime.ofInstant(now, ZoneId.of(timezone));
            return localNow.getHour() == chosenHour;
        } catch (Exception e) {
            // ZoneId.of throws on anything that isn't a known zone id, which is the realistic way a
            // stored timezone goes bad - a client sending an abbreviation or a UTC offset string.
            log.warn("Skipping user {} in the daily summary sweep - could not resolve their local hour",
                    user.userId(), e);
            return false;
        }
    }

    // Public rather than private so InternalNotificationController can run a single zone on demand,
    // skipping the hour check entirely - that bypass is the whole point of the manual trigger, since
    // otherwise testing a summary means waiting for somebody's chosen hour to come round.
    public void processUsersForTimezone(String timezone) {
        List<ProfileServiceClient.UserPreferenceResponse> users =
                profileServiceClient.getUsersForDailySummary(timezone);

        if (users == null) {
            return;
        }
        if (users.isEmpty()) {
            return;
        }

        log.info("Found {} opted-in users for timezone {}. Fetching bulk balances.", users.size(), timezone);

        Map<Long, AccountServiceClient.UserAggregateBalanceResponse> balanceMap = fetchBalanceMap(users);

        // No per-user try/catch on this path on purpose: the caller asked for one specific zone and
        // needs to be told when it didn't work, which is what the controller turns into a 502.
        for (ProfileServiceClient.UserPreferenceResponse user : users) {
            dispatchSummary(user, balanceMap.get(user.userId()));
        }
    }

    // One batch call for everyone being mailed this run, rather than a balance lookup per user -
    // the N+1 this job exists to avoid.
    private Map<Long, AccountServiceClient.UserAggregateBalanceResponse> fetchBalanceMap(
            List<ProfileServiceClient.UserPreferenceResponse> users) {
        List<Long> userIds = users.stream()
                .map(ProfileServiceClient.UserPreferenceResponse::userId)
                .toList();

        List<AccountServiceClient.UserAggregateBalanceResponse> balances =
                accountServiceClient.getAggregateBalancesBatch(userIds);

        // Convert balance list into a Map for O(1) instantaneous lookup during the loop
        // learned collectors.tomap needs a key function and a value function, here the value
        // function is just identity, keeping the whole record as the map's value unchanged
        return balances.stream()
                .collect(Collectors.toMap(
                        AccountServiceClient.UserAggregateBalanceResponse::userId,
                        b -> b
                ));
    }

    // The in-memory join of one user against the batch of balances, then the send.
    private void dispatchSummary(ProfileServiceClient.UserPreferenceResponse user,
                                 AccountServiceClient.UserAggregateBalanceResponse userBalance) {
        if (userBalance == null) {
            return;
        }

        String emailHtml = buildHtmlSummary(userBalance);

        // The address arrives on the same preferences record that selected this user for a
        // summary in the first place. Users registered before the email field existed have none;
        // skip them rather than dispatching to a fabricated address that can never be delivered.
        // Recorded as FAILED rather than passed over silently, the same way TransactionAlertListener
        // treats this case - "we owed you a summary and could not send it" is exactly the kind of
        // thing the notification feed exists to show.
        String userEmail = user.email();
        if (userEmail == null || userEmail.isBlank()) {
            log.warn("Skipping the daily summary for user {} - no email address on file", user.userId());
            persistRecord(user.userId(), emailHtml, NotificationStatus.FAILED);
            return;
        }

        boolean dispatched = notificationProviderService.dispatchEmail(userEmail, EMAIL_SUBJECT, emailHtml);

        persistRecord(user.userId(), emailHtml, dispatched ? NotificationStatus.SENT : NotificationStatus.FAILED);
    }

    // Same shape as TransactionAlertListener.persistRecord - this job was the one dispatcher that
    // never left a durable trace, so its emails were invisible in GET /api/v1/notifications.
    private void persistRecord(Long userId, String message, NotificationStatus status) {
        NotificationRecord record = new NotificationRecord();
        record.setUserId(userId);
        record.setType(NotificationType.DAILY_SUMMARY);
        record.setChannel(NotificationChannel.EMAIL);
        record.setSubject(EMAIL_SUBJECT);
        record.setMessage(message);
        record.setStatus(status);
        notificationRecordRepository.save(record);
    }

    private String buildHtmlSummary(AccountServiceClient.UserAggregateBalanceResponse balanceData) {
        return """
               <html>
                   <body>
                       <h2>Good Morning!</h2>
                       <p>Here is your daily aggregate balance summary:</p>
                       <div style="font-size: 24px; font-weight: bold; color: #2E86C1;">
                           Total Aggregate Balance: $%s
                       </div>
                       <p>Thank you for banking with us.</p>
                   </body>
               </html>
               """.formatted(balanceData.totalBalance());
    }
}