package com.qms.dashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Defaults for the threshold-alert sweep (SRS §15.4, ticket 47). The SRS requires the grouping window (FR-MON-023)
 * and the escalation delay (FR-MON-021) to be "configurable" but gives no numeric default, so these are this
 * build's choice — a per-Service row in {@code service_alert_threshold} may override either for its own alerts
 * (null keeps the default); {@code break_overrun} (FR-AGT-023, no Service of its own) always uses these.
 *
 * @param defaultGroupWindowMinutes how long a still-open alert keeps grouping fresh breaches of the same key before
 *     a further breach starts a new alert instead (default 15 minutes)
 * @param defaultEscalationDelayMinutes how long an unacknowledged alert waits before escalating to Org Admin
 *     (default 30 minutes); 0 switches escalation off. {@link ThresholdAlertScheduler} and {@link
 *     AlertEscalationScheduler}'s own sweep schedules are the {@code qms.alerts.threshold-check-cron} and {@code
 *     qms.alerts.escalation-check-cron} properties directly (a test disables either with {@code -}), the same
 *     convention {@code qms.dashboard.refresh-cron} uses rather than a field here.
 */
@ConfigurationProperties("qms.alerts")
public record AlertProperties(@DefaultValue("15") int defaultGroupWindowMinutes, @DefaultValue("30") int defaultEscalationDelayMinutes) {}
