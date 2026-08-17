package com.example.notificationservice.controller;

import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.notificationservice.job.DailyBalanceSummaryJob;

/**
 * Exposes an operator handle on {@code DailyBalanceSummaryJob}.
 *
 * <p>The job is otherwise only reachable by waiting for its hourly cron to line up with some user's
 * chosen summary hour in their own timezone. Since that hour became a per-user setting there is no
 * configuration that brings the moment forward, so this endpoint is the only way to exercise the job
 * on demand.
 *
 * <p>The path sits under {@code /api/v1/internal/} because that is the one prefix
 * {@code k8s/08-ingress-routes.yaml} does not route, leaving it unreachable from outside the
 * cluster; it carries no user authentication and is gated only by {@code InternalTokenFilter}. Do
 * not move it and do not add the prefix to the ingress — this endpoint sends real email, so a routed
 * version is an open relay for anyone who can reach the load balancer.
 */
@RestController
public class InternalNotificationController {

    private static final Logger log = LoggerFactory.getLogger(InternalNotificationController.class);

    private final DailyBalanceSummaryJob dailyBalanceSummaryJob;

    public InternalNotificationController(DailyBalanceSummaryJob dailyBalanceSummaryJob) {
        this.dailyBalanceSummaryJob = dailyBalanceSummaryJob;
    }

    /**
     * Triggers the daily balance summary immediately, and sends real email as a result.
     *
     * <p>With a timezone, that one zone runs unconditionally: the per-user hour check is skipped
     * entirely, which is what makes a same-minute end-to-end check possible. Without one, the exact
     * sweep the cron would have run executes instead, so the scheduled path itself can be exercised
     * — meaning users who are not due this hour receive nothing.
     *
     * <p>The two modes also differ in how failure surfaces. The full sweep isolates each user and
     * always reports 200; the single-zone path deliberately does not swallow failures, because a
     * caller naming one zone needs to be told it did not work, so a downstream outage comes back as
     * 502 naming the unreachable service rather than as a silent success.
     *
     * @param timezone optional; when present must be an IANA zone id such as
     *     {@code America/New_York} — anything else is rejected with 400 naming the bad value rather
     *     than surfacing as a {@code ZoneRulesException} from inside the job
     * @return 200 on a completed run, 400 for an unknown zone id, or 502 when a downstream lookup
     *     failed during a single-zone run
     */
    @PostMapping("/api/v1/internal/notifications/daily-summary/run")
    public ResponseEntity<String> runDailySummary(@RequestParam(required = false) String timezone) {
        if (timezone == null || timezone.isBlank()) {
            log.info("Manual trigger: running the full daily summary sweep.");
            dailyBalanceSummaryJob.processDailySummaries();
            return ResponseEntity.ok("Daily summary sweep triggered for every user whose chosen hour is now.");
        }

        if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
            return ResponseEntity.badRequest().body("Unknown timezone: " + timezone
                    + ". Expected an IANA zone id, e.g. America/New_York.");
        }

        log.info("Manual trigger: running the daily summary for timezone {}, bypassing the hour check.", timezone);

        try {
            dailyBalanceSummaryJob.processUsersForTimezone(timezone);
        } catch (Exception e) {
            log.error("Manual daily summary run failed for timezone {}", timezone, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("Daily summary run failed for " + timezone + ": " + e.getMessage()
                            + ". Check that profile-service (8082) and account-service (8083) are running.");
        }

        return ResponseEntity.ok("Daily summary triggered for timezone " + timezone + ".");
    }
}
