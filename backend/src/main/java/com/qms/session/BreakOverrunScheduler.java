package com.qms.session;

import com.qms.platform.Profiles;
import com.qms.platform.alerts.ThresholdAlertContext;
import com.qms.platform.alerts.ThresholdAlertRaiser;
import com.qms.platform.alerts.ThresholdAlertTypes;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * A break exceeding its type's own maximum duration raises a dashboard alert to the Team Admin (FR-AGT-023). Unlike
 * {@link BreakRules#overran}, which grades a break already ended for the report (FR-AGT-022), this sweep watches one
 * still running: {@link ThresholdAlertRaiser} (implemented in {@code com.qms.dashboard}, never imported here —
 * ArchitectureTest's package-cycle rule) groups a breach that persists across ticks into the same still-open alert
 * rather than raising it again (FR-MON-023), keyed on the break's own counter session so two Agents overrunning at
 * once never collide. A break has no Service of its own (it belongs to an Agent's session), so {@code serviceId} is
 * always null here (SRS §15.4's five FR-MON-020 thresholds are the only ones a Service configures). The schedule is
 * a property so a test can turn it off ({@code qms.session.break-overrun-check-cron=-}) and drive a tick itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
public class BreakOverrunScheduler {

    private final BreakOverrunReads reads;
    private final ThresholdAlertRaiser raiser;
    private final Clock clock;

    BreakOverrunScheduler(BreakOverrunReads reads, ThresholdAlertRaiser raiser, Clock clock) {
        this.reads = reads;
        this.raiser = raiser;
        this.clock = clock;
    }

    @Scheduled(cron = "${qms.session.break-overrun-check-cron:*/30 * * * * *}", zone = "UTC")
    public void tick() {
        Instant now = clock.instant();
        for (BreakOverrunReads.OpenBreak open : reads.openWithMaximum()) {
            long elapsedMinutes = Duration.between(open.startedAt(), now).toSeconds() / 60;
            if (elapsedMinutes > open.maxMinutes()) {
                raiser.raise(new ThresholdAlertContext(
                        open.siteId(), null, ThresholdAlertTypes.BREAK_OVERRUN, open.counterSessionId(),
                        BigDecimal.valueOf(elapsedMinutes), BigDecimal.valueOf(open.maxMinutes()), now));
            }
        }
    }
}
