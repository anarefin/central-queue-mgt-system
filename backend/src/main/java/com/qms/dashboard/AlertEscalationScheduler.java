package com.qms.dashboard;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.Profiles;
import com.qms.platform.notifications.NotificationContext;
import com.qms.platform.notifications.NotificationTrigger;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Escalates an unacknowledged alert to Org Admin once its configured delay has passed (FR-MON-021's "MAY escalate
 * ... after a configurable delay"). Org Admin already watches every Site's {@code site:{id}:alerts} and {@code
 * staff-alert:{site_id}} topics from the moment an alert first raises (§5.2: "View live dashboard (all groups)"), so
 * escalation is not a wider audience but a second, concrete nudge — a repeat of the mapped staff-alert trigger
 * (§14.2) and an audited {@code alert.escalated} marker on the row itself, visible the next time it is read. It
 * never re-raises {@code alert.raised}: repeating that event for an alert already open would read as a second
 * breach, which FR-MON-023 reserves for an actual new one.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
public class AlertEscalationScheduler {

    private final AlertRepository alerts;
    private final AlertProperties properties;
    private final NotificationTrigger notifications;
    private final AuditWriter audit;
    private final Clock clock;

    AlertEscalationScheduler(AlertRepository alerts, AlertProperties properties, NotificationTrigger notifications, AuditWriter audit, Clock clock) {
        this.alerts = alerts;
        this.properties = properties;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    @Scheduled(cron = "${qms.alerts.escalation-check-cron:*/30 * * * * *}", zone = "UTC")
    public void tick() {
        Instant now = clock.instant();
        for (Alert alert : alerts.dueForServiceEscalation(now)) escalate(alert, now);
        // 0 is the "no escalation" sentinel (AlertProperties): skip the default-delay branch entirely rather than
        // escalate everything whose Service has no override, the instant it is raised.
        if (properties.defaultEscalationDelayMinutes() > 0) {
            for (Alert alert : alerts.dueForDefaultEscalation(now, properties.defaultEscalationDelayMinutes())) escalate(alert, now);
        }
    }

    private void escalate(Alert alert, Instant now) {
        alerts.escalate(alert.id(), now);
        notifications.fire(
                AlertRules.triggerKeyFor(alert.thresholdType()),
                new NotificationContext(alert.siteId(), alert.serviceId(), null, null, null, null, now, null, null));
        audit.record(AuditEvent.of("alert.escalated", "alert", alert.id()).withAfter(Map.of("threshold_type", alert.thresholdType())));
    }
}
