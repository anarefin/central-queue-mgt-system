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
 * Delivers to a visitor with the ticket page open (SRS §14.1, ticket 37): publishes on the ticket's own realtime
 * topic, the one {@code com.qms.queue.TicketEvents} already announces every transition on, so no new client
 * subscription is needed. A message with no ticket cannot use this channel — that is the {@code staff_alert}
 * channel's own reach (§14.1: in-app realtime is "visitors with the app open, and staff consoles").
 */
@Component
@Profile(Profiles.SERVING)
class InAppRealtimeChannel implements NotificationChannel {

    static final String KEY = "in_app";

    private final RealtimePublisher realtime;
    private final Clock clock;

    InAppRealtimeChannel(RealtimePublisher realtime, Clock clock) {
        this.realtime = realtime;
        this.clock = clock;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Outcome send(MessageRow message) {
        if (message.ticketId() == null) return Outcome.failure("no_ticket_topic");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notification_id", message.id().toString());
        data.put("trigger", message.triggerKey());
        data.put("subject", message.renderedSubject());
        data.put("body", message.renderedBody());
        realtime.publish(Topics.ticket(message.ticketId()), "notification." + message.triggerKey(), clock.instant(), data);
        return Outcome.success("delivered");
    }
}
