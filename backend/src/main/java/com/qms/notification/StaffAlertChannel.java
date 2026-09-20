package com.qms.notification;

import com.qms.notification.NotificationMessageRepository.MessageRow;
import com.qms.platform.Profiles;
import com.qms.platform.realtime.RealtimePublisher;
import com.qms.platform.realtime.Topics;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Delivers to the team and org admins of a Site (SRS §14.1): publishes on that Site's own alert topic
 * ({@link NotificationTopics}), which a console or admin surface can subscribe to for live alerts. This ticket
 * registers the channel and the topic's own authorisation (FR-CFG-103); the operational triggers that would call it
 * (queue SLA breach, break overrun, counter unattended, device offline) are later tickets' own work (ticket 46, 47).
 */
@Component
@Profile(Profiles.SERVING)
class StaffAlertChannel implements NotificationChannel {

    static final String KEY = "staff_alert";

    private final RealtimePublisher realtime;
    private final Clock clock;

    StaffAlertChannel(RealtimePublisher realtime, Clock clock) {
        this.realtime = realtime;
        this.clock = clock;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Outcome send(MessageRow message) {
        if (message.siteId() == null) return Outcome.failure("no_site_topic");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notification_id", message.id().toString());
        data.put("trigger", message.triggerKey());
        data.put("subject", message.renderedSubject());
        data.put("body", message.renderedBody());
        realtime.publish(Topics.staffAlert(message.siteId()), "notification." + message.triggerKey(), clock.instant(), data);
        return Outcome.success("delivered");
    }
}
