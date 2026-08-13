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

// An operator handle on DailyBalanceSummaryJob, which is otherwise only reachable by waiting for its
// hourly cron to line up with some user's chosen summary hour in their own timezone - a slow way to
// find out whether email delivery actually works. Since the summary hour became a per-user setting
// there is no configuration that can bring that moment forward, so this endpoint is the only way to
// exercise the job on demand.
//
// Lives under /api/v1/internal/ for the same reason profile-service's InternalPreferenceController
// does: it is unauthenticated, and that is the ONE prefix k8s/08-ingress-routes.yaml does not route,
// so it is unreachable from outside the cluster. Do not move it, and do not add /api/v1/internal to
// the ingress - this endpoint sends real email, so a routed version of it would be an open relay for
// anyone who could reach the load balancer.
@RestController
public class InternalNotificationController {

    private static final Logger log = LoggerFactory.getLogger(InternalNotificationController.class);

    private final DailyBalanceSummaryJob dailyBalanceSummaryJob;

    public InternalNotificationController(DailyBalanceSummaryJob dailyBalanceSummaryJob) {
        this.dailyBalanceSummaryJob = dailyBalanceSummaryJob;
    }

    // With a timezone, runs that one zone immediately and unconditionally - the hour check is skipped,
    // which is what makes this useful for a same-minute end-to-end check. Without one, runs the exact
    // sweep the cron would have run, so the scheduled path itself can be exercised on demand.
    @PostMapping("/api/v1/internal/notifications/daily-summary/run")
    public ResponseEntity<String> runDailySummary(@RequestParam(required = false) String timezone) {
        if (timezone == null || timezone.isBlank()) {
            log.info("Manual trigger: running the full daily summary sweep.");
            dailyBalanceSummaryJob.processDailySummaries();
            return ResponseEntity.ok("Daily summary sweep triggered for every user whose chosen hour is now.");
        }

        // Validated up front so a typo comes back as a 400 naming the bad zone, rather than surfacing
        // as a ZoneRulesException from somewhere inside the job.
        if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
            return ResponseEntity.badRequest().body("Unknown timezone: " + timezone
                    + ". Expected an IANA zone id, e.g. America/New_York.");
        }

        log.info("Manual trigger: running the daily summary for timezone {}, bypassing the hour check.", timezone);

        // processUsersForTimezone deliberately does NOT swallow failures - the scheduled sweep wraps
        // each zone in its own try/catch so one region's outage can't abort the rest, but a caller
        // asking for one specific zone needs to be told it didn't work. Translated here rather than
        // left to propagate, because a raw Feign exception surfaces as a bodyless 500 that says
        // nothing about which downstream was unreachable - and "profile-service isn't running yet"
        // is by far the most likely reason this endpoint fails in local use.
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
