package com.qms.integration.webhook;

import com.qms.platform.Profiles;
import com.qms.platform.realtime.RealtimeEventOccurred;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a {@link RealtimeEventOccurred} into a queued webhook delivery for every active endpoint subscribed to its
 * type (FR-INT-020): the one listener this seam has. Runs after the originating transition's own transaction has
 * already committed (the same after-commit point {@code RealtimeHub} itself dispatches from) and, whatever happens
 * here, {@code RealtimeHub.publish} already caught and logged it before this method could even be reached, so a
 * failure here can never surface as an error to whatever queue action raised the event (FR-INT-022).
 *
 * <p>Deliberately not itself {@code @Transactional}: this method runs for every realtime event, system-wide, on the
 * hot path of every queue action, and the overwhelming majority of calls have no subscriber at all. The one DB read
 * needed to know that ({@link WebhookEndpointRepository#activeSubscribers}) is a single short-lived query, not a
 * held-open transaction; only once there is something to write does {@link WebhookEventRecorder} — a separate bean,
 * so its own {@code @Transactional} is reached through Spring's proxy rather than a same-class call that would
 * silently skip it — open one.
 *
 * <p>Deduplicates across a single business transition's own topic fan-out (queue:/ticket:/counter:/zone: for one
 * {@code ticket.called}, see {@code queue.TicketEvents}): {@link WebhookDeliveryRepository#recordEvent} is keyed on
 * type + occurred_at + the event's own data, which is the same object across every one of those fan-out calls, so
 * only the first of them records the event and queues a delivery; the rest see the key already taken and do nothing.
 */
@Component
@Profile(Profiles.SERVING)
class WebhookEventListener {

    private final WebhookEndpointRepository endpoints;
    private final WebhookEventRecorder recorder;
    private final JsonMapper mapper;
    private final Clock clock;

    WebhookEventListener(WebhookEndpointRepository endpoints, WebhookEventRecorder recorder, JsonMapper mapper, Clock clock) {
        this.endpoints = endpoints;
        this.recorder = recorder;
        this.mapper = mapper;
        this.clock = clock;
    }

    @EventListener
    void onRealtimeEvent(RealtimeEventOccurred event) {
        Optional<WebhookEventType> known = WebhookEventType.fromWire(event.type());
        if (known.isEmpty()) return; // not one of §21.4's closed set; never delivered, whatever an endpoint asked for
        List<UUID> subscribers = endpoints.activeSubscribers(event.type());
        if (subscribers.isEmpty()) return; // nobody would receive it; not worth a transaction, let alone a record

        recorder.record(dedupKey(event), event, subscribers, clock.instant());
    }

    private String dedupKey(RealtimeEventOccurred event) {
        return event.type() + "|" + event.occurredAt() + "|" + mapper.writeValueAsString(event.data());
    }
}
