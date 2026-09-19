package com.qms.queue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ticket event log, and the only way into it. Every transition of a ticket writes exactly one row (Invariant 3)
 * carrying the originating device's time and the server's time (ADR-0001) and the next number of that ticket's own
 * sequence (FR-QUE-070). It joins the caller's transaction, so the transition and its event commit together.
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

    TicketEvents(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
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
        return seq;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
