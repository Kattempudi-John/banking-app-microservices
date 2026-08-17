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

/**
 * Emails each opted-in user an aggregate balance summary at the hour they chose, in their own
 * timezone.
 *
 * <p>The sweep runs hourly rather than once a day because the summary hour is a per-user setting;
 * each run mails only the roughly one twenty-fourth of the population whose local clock currently
 * reads their chosen hour.
 *
 * <p>Nothing here fires unless {@code @EnableScheduling} is present on the application class. The
 * cron expression alone is inert, and the failure is silent — no summary is ever sent and no error
 * is logged.
 */
@Component
public class DailyBalanceSummaryJob {

    private static final Logger log = LoggerFactory.getLogger(DailyBalanceSummaryJob.class);

    private static final String EMAIL_SUBJECT = "Your Daily Balance Summary";

    private final ProfileServiceClient profileServiceClient;
    private final AccountServiceClient accountServiceClient;
    private final NotificationProviderService notificationProviderService;
    private final NotificationRecordRepository notificationRecordRepository;
    private final Clock clock;

    /**
     * Creates the job.
     *
     * @param clock read on every sweep instead of calling {@code Instant.now()} directly, so a test
     *     can freeze time at a chosen instant and assert which users come out due
     */
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

    /**
     * Runs the hourly sweep, mailing every opted-in user whose local clock currently reads their
     * chosen summary hour.
     *
     * <p>Fetches the whole opted-in population in one call and filters in memory. Querying zone by
     * zone instead would mean roughly 600 HTTP calls an hour: with a per-user hour every zone is
     * potentially a target, so narrowing by zone narrows nothing. The cost of this shape is that it
     * grows with the customer base rather than with the work being done — at a size where the list
     * stops fitting comfortably in memory this wants profile-service returning only the users whose
     * local hour is now, rather than a bigger heap here.
     *
     * <p>Never throws. A failed population fetch abandons the sweep, and a failure mailing one user
     * is logged and skipped so it cannot cost everyone else their summary; an exception escaping
     * into the scheduler thread would show up only as a cron that stopped running.
     *
     * <p>Each mailed user gets a {@code NotificationRecord}, {@code SENT} or {@code FAILED}. A user
     * who is due but missing from the batch balance response is skipped with no record at all, so
     * their absence from the feed is the only trace.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void processDailySummaries() {
        Instant now = Instant.now(clock);
        log.info("Starting the hourly Daily Balance Summary sweep.");

        List<ProfileServiceClient.UserPreferenceResponse> optedInUsers;
        try {
            optedInUsers = profileServiceClient.getAllUsersForDailySummary();
        } catch (Exception e) {
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
                log.error("Failed to send the daily summary for user {}", user.userId(), e);
            }
        }
    }

    private boolean isDueThisHour(ProfileServiceClient.UserPreferenceResponse user, Instant now) {
        try {
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
            log.warn("Skipping user {} in the daily summary sweep - could not resolve their local hour",
                    user.userId(), e);
            return false;
        }
    }

    /**
     * Mails every opted-in user in one timezone immediately, whatever hour they chose.
     *
     * <p>The hour check is skipped entirely — that bypass is the reason this is public rather than
     * private, since it is what makes an on-demand end-to-end check possible instead of waiting for
     * somebody's chosen hour to come round. Calling it outside a manual trigger sends users a
     * summary at a time they did not ask for.
     *
     * <p>Unlike the scheduled sweep this does not isolate failures: a downstream outage or an
     * undeliverable address propagates to the caller, who asked about one specific zone and needs to
     * be told it did not work. {@code InternalNotificationController} turns that into a 502.
     *
     * @param timezone must be a valid IANA zone id; the caller is expected to have validated it,
     *     since an unknown value reaches profile-service as-is
     */
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

        for (ProfileServiceClient.UserPreferenceResponse user : users) {
            dispatchSummary(user, balanceMap.get(user.userId()));
        }
    }

    private Map<Long, AccountServiceClient.UserAggregateBalanceResponse> fetchBalanceMap(
            List<ProfileServiceClient.UserPreferenceResponse> users) {
        List<Long> userIds = users.stream()
                .map(ProfileServiceClient.UserPreferenceResponse::userId)
                .toList();

        List<AccountServiceClient.UserAggregateBalanceResponse> balances =
                accountServiceClient.getAggregateBalancesBatch(userIds);

        return balances.stream()
                .collect(Collectors.toMap(
                        AccountServiceClient.UserAggregateBalanceResponse::userId,
                        b -> b
                ));
    }

    private void dispatchSummary(ProfileServiceClient.UserPreferenceResponse user,
                                 AccountServiceClient.UserAggregateBalanceResponse userBalance) {
        if (userBalance == null) {
            return;
        }

        String emailHtml = buildHtmlSummary(userBalance);

        String userEmail = user.email();
        if (userEmail == null || userEmail.isBlank()) {
            log.warn("Skipping the daily summary for user {} - no email address on file", user.userId());
            persistRecord(user.userId(), emailHtml, NotificationStatus.FAILED);
            return;
        }

        boolean dispatched = notificationProviderService.dispatchEmail(userEmail, EMAIL_SUBJECT, emailHtml);

        persistRecord(user.userId(), emailHtml, dispatched ? NotificationStatus.SENT : NotificationStatus.FAILED);
    }

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