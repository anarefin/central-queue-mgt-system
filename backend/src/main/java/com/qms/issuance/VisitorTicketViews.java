package com.qms.issuance;

import com.qms.issuance.TicketRepository.TicketRecord;
import com.qms.queue.QueueReads;
import com.qms.queue.WaitEstimates;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * What a visitor may see of their own ticket (§20.2, ticket 37): its live position and estimated wait as a range
 * (FR-MOB-013), the token currently being served for the same Service, and the floor, Zone and optional wayfinding
 * image to find their way there (FR-MOB-032). Returns a plain map, the same shape {@code TopicSource#snapshot} and
 * {@code StreamController#snapshot} already use, so the WebSocket snapshot and the REST read never drift apart.
 *
 * <p>Not {@code @Profile(SERVING)}: {@link TicketTopics} (which is not profile-restricted either, the same as every
 * other {@code TopicSource}) depends on it, and {@code RealtimeHub} wires every {@code TopicSource} in every profile.
 */
@Component
class VisitorTicketViews {

    private final TicketRepository tickets;
    private final QueueReads queues;
    private final WaitEstimates estimates;
    private final Clock clock;

    VisitorTicketViews(TicketRepository tickets, QueueReads queues, WaitEstimates estimates, Clock clock) {
        this.tickets = tickets;
        this.queues = queues;
        this.estimates = estimates;
        this.clock = clock;
    }

    Map<String, Object> view(TicketRecord t) {
        Integer position = queues.positionOf(t.id());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticket_id", t.id().toString());
        data.put("token_number", t.tokenNumber());
        data.put("state", t.state());
        data.put("service_id", t.serviceId().toString());
        data.put("position", position);
        data.put("estimated_wait_minutes", estimates.atPosition(t.serviceId(), position));
        data.put("now_serving_token_number", tickets.nowServingToken(t.serviceId()).orElse(null));
        data.put("zone", zone(t));
        data.put("updated_at", clock.instant().toString());
        return data;
    }

    private static Map<String, Object> zone(TicketRecord t) {
        if (t.zoneId() == null) return null;
        Map<String, Object> zone = new LinkedHashMap<>();
        zone.put("id", t.zoneId().toString());
        zone.put("name", t.zoneName());
        zone.put("building_label", t.buildingLabel());
        zone.put("floor_label", t.floorLabel());
        zone.put("wayfinding_image_url", t.wayfindingImageUrl());
        return zone;
    }
}
