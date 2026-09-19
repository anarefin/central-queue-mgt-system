package com.qms.queue;

import com.qms.platform.realtime.RealtimePublisher;
import com.qms.platform.realtime.Topics;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ticket event log, and the only way into it. Every transition of a ticket writes exactly one row (Invariant 3)
 * carrying the originating device's time and the server's time (ADR-0001) and the next number of that ticket's own
 * sequence (FR-QUE-070). It joins the caller's transaction, so the transition and its event commit together.
 *
 * <p>The same transition is published to the realtime hub (SRS §21.4) on the queue of its Service and, when a counter took
 * part, on that counter's topic. The hub delivers it only once the transaction commits, so a subscriber never hears of a
 * transition that was rolled back. A transition that moves the queue also publishes the recomputed wait estimate and the
 * new places of the tickets it moved ({@link EstimateEvents}).
 */
@Repository
public class TicketEvents {

    /** One transition. {@code fromState} is null for a ticket's first event; {@code counterId} and {@code payload} may be null. */
    public record Transition(
            UUID ticketId,
            String eventType,
            String fromState,
            String toState,
            UUID actorId,
            String actorType,
            UUID counterId,
            Object payload,
            Instant deviceTime,
            Instant serverTime) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final RealtimePublisher realtime;
    private final EstimateEvents estimates;

    TicketEvents(JdbcTemplate jdbc, JsonMapper mapper, RealtimePublisher realtime, EstimateEvents estimates) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.realtime = realtime;
        this.estimates = estimates;
    }

    /** Appends the event and returns its per-ticket sequence number, one more than the ticket's last. */
    public int append(Transition transition) {
        Integer last = jdbc.queryForObject("SELECT coalesce(max(seq), 0) FROM ticket_event WHERE ticket_id = ?", Integer.class, transition.ticketId());
        int seq = (last == null ? 0 : last) + 1;
        jdbc.update(
                "INSERT INTO ticket_event (id, ticket_id, seq, event_type, from_state, to_state, actor_id, actor_type, counter_id, payload, occurred_at, recorded_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                UUID.randomUUID(),
                transition.ticketId(),
                seq,
                transition.eventType(),
                transition.fromState(),
                transition.toState(),
                transition.actorId(),
                transition.actorType(),
                transition.counterId(),
                transition.payload() == null ? null : mapper.writeValueAsString(transition.payload()),
                ts(transition.deviceTime()),
                ts(transition.serverTime()));
        publish(transition);
        return seq;
    }

    /**
     * Tells the hub. The waiting count is read here, inside the transaction, so it is the count this transition leaves
     * behind; it travels with the event so a console can keep its number without asking again.
     */
    private void publish(Transition transition) {
        var facts = jdbc.queryForMap("SELECT service_id, token_number FROM ticket WHERE id = ?", transition.ticketId());
        UUID serviceId = (UUID) facts.get("service_id");
        Integer waiting = jdbc.queryForObject("SELECT count(*) FROM ticket WHERE service_id = ? AND state IN ('waiting', 'paused')", Integer.class, serviceId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticket_id", transition.ticketId().toString());
        data.put("token_number", facts.get("token_number"));
        data.put("service_id", serviceId.toString());
        data.put("state", transition.toState());
        if (transition.counterId() != null) data.put("counter_id", transition.counterId().toString());
        data.put("waiting_count", waiting == null ? 0 : waiting);
        if (transition.payload() instanceof Map<?, ?> payload) {
            // Displays deduplicate an announcement by ticket and announce_count (FR-QUE-083).
            for (String key : List.of("announce", "announce_count")) if (payload.containsKey(key)) data.put(key, payload.get(key));
        }
        realtime.publish(Topics.queue(serviceId), transition.eventType(), transition.deviceTime(), data);
        if (transition.counterId() != null) realtime.publish(Topics.counter(transition.counterId()), transition.eventType(), transition.deviceTime(), data);
        // A transition that moves the queue changes its estimate and the places of the tickets behind it (FR-QUE-042).
        estimates.transitioned(serviceId, transition.fromState(), transition.toState(), transition.deviceTime());
    }

    /** The ticket's recorded changes of state, oldest first, for the durations that are worked out from them (Invariant 1). */
    public List<TicketTimings.Change> changes(UUID ticketId) {
        return jdbc.query(
                "SELECT occurred_at, from_state, to_state FROM ticket_event WHERE ticket_id = ? ORDER BY seq",
                (rs, i) -> new TicketTimings.Change(rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), rs.getString("from_state"), rs.getString("to_state")),
                ticketId);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
