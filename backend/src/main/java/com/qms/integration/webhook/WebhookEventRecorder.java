package com.qms.integration.webhook;

import com.qms.platform.Profiles;
import com.qms.platform.realtime.RealtimeEventOccurred;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write side of {@link WebhookEventListener}, split into its own bean so {@code @Transactional} (and the DB
 * connection it holds) is only ever acquired when there is actually something to write — {@link WebhookEventListener}
 * itself stays a plain, un-transactional {@code @EventListener} that can rule out "no subscriber" with a single,
 * short-lived read. {@code RealtimeHub.publish} runs for every realtime event system-wide, on the hot path of every
 * queue action; an unconditional transaction there, even a fast one, is exactly the kind of avoidable per-call cost
 * FR-INT-022 ("failures never affect queue operation") is written against.
 */
@Component
@Profile(Profiles.SERVING)
class WebhookEventRecorder {

    private final WebhookDeliveryRepository deliveries;

    WebhookEventRecorder(WebhookDeliveryRepository deliveries) {
        this.deliveries = deliveries;
    }

    @Transactional
    void record(String dedupKey, RealtimeEventOccurred event, List<UUID> subscribers, Instant now) {
        Optional<UUID> eventId = deliveries.recordEvent(dedupKey, event.type(), event.occurredAt(), event.data(), now);
        if (eventId.isEmpty()) return; // already recorded by an earlier topic fan-out of this same transition
        for (UUID endpointId : subscribers) deliveries.queueDelivery(eventId.get(), endpointId, event.type(), now);
    }
}
