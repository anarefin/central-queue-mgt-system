package com.qms.dashboard;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.Profiles;
import com.qms.platform.alerts.ThresholdAlertContext;
import com.qms.platform.alerts.ThresholdAlertRaiser;
import com.qms.platform.notifications.NotificationContext;
import com.qms.platform.notifications.NotificationTrigger;
import com.qms.platform.realtime.RealtimePublisher;
import com.qms.platform.realtime.Topics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one implementation of {@link ThresholdAlertRaiser} (SRS §15.4, §11.3 FR-AGT-023, ticket 47): groups a repeated
 * breach of the same key into the still-open alert it already has (FR-MON-023), or opens a new one, fires the
 * mapped staff-alert trigger (§14.2) and publishes {@code alert.raised} on {@code site:{id}:alerts} (§21.2, §21.4).
 * A grouped breach fires neither: "grouped, not repeated" (FR-MON-023) covers the notification and the realtime
 * event as much as the alert row itself.
 */
@Service
@Profile(Profiles.SERVING)
class AlertEvaluationService implements ThresholdAlertRaiser {

    private final AlertRepository alerts;
    private final AlertThresholdRepository thresholds;
    private final AlertProperties properties;
    private final NotificationTrigger notifications;
    private final RealtimePublisher realtime;
    private final AuditWriter audit;
    private final Clock clock;

    AlertEvaluationService(
            AlertRepository alerts,
            AlertThresholdRepository thresholds,
            AlertProperties properties,
            NotificationTrigger notifications,
            RealtimePublisher realtime,
            AuditWriter audit,
            Clock clock) {
        this.alerts = alerts;
        this.thresholds = thresholds;
        this.properties = properties;
        this.notifications = notifications;
        this.realtime = realtime;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void raise(ThresholdAlertContext context) {
        Instant now = context.occurredAt() != null ? context.occurredAt() : clock.instant();
        Instant since = now.minus(Duration.ofMinutes(groupWindowMinutesFor(context.serviceId())));
        Optional<Alert> existing = alerts.findGroupable(context.siteId(), context.serviceId(), context.thresholdType(), context.subjectId(), since);
        if (existing.isPresent()) {
            // FR-MON-023: a repeated breach of an already-open alert is grouped, not a new row, notification or event.
            alerts.recordBreach(existing.get().id(), now, context.measuredValue());
            return;
        }

        UUID id = alerts.insert(
                context.siteId(), context.serviceId(), context.thresholdType(), context.subjectId(), context.measuredValue(), context.thresholdValue(), now);

        notifications.fire(
                AlertRules.triggerKeyFor(context.thresholdType()),
                new NotificationContext(context.siteId(), context.serviceId(), null, null, null, null, now, null, null));

        Map<String, Object> data = eventData(id, context);
        realtime.publish(Topics.alerts(context.siteId()), "alert.raised", now, data);
        audit.record(AuditEvent.of("alert.raised", "alert", id).withAfter(data));
    }

    private int groupWindowMinutesFor(UUID serviceId) {
        if (serviceId != null) {
            Optional<AlertThreshold> threshold = thresholds.find(serviceId);
            if (threshold.isPresent() && threshold.get().groupWindowMinutes() != null) return threshold.get().groupWindowMinutes();
        }
        return properties.defaultGroupWindowMinutes();
    }

    private static Map<String, Object> eventData(UUID id, ThresholdAlertContext context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alert_id", id.toString());
        data.put("site_id", context.siteId().toString());
        data.put("service_id", context.serviceId() == null ? null : context.serviceId().toString());
        data.put("threshold_type", context.thresholdType());
        data.put("measured_value", context.measuredValue());
        data.put("threshold_value", context.thresholdValue());
        return data;
    }
}
