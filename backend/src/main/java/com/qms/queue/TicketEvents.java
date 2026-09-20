package com.qms.queue;

import com.qms.platform.notifications.NotificationContext;
import com.qms.platform.notifications.NotificationTrigger;
import com.qms.platform.notifications.NotificationTriggerKeys;
import com.qms.platform.realtime.RealtimePublisher;
import com.qms.platform.realtime.Topics;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * part, on that counter's topic and on that counter's zone's topic ({@code zone:}, ticket 28, FR-DSP-010): a display
 * board watches its whole zone, not one counter, so it hears every call in it within the hub's own delivery latency.
 * The hub delivers it only once the transaction commits, so a subscriber never hears of a transition that was rolled
 * back. A transition that moves the queue also publishes the recomputed wait estimate and the new places of the
 * tickets it moved ({@link EstimateEvents}).
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

    /** The states in which a ticket is bound to a Counter session (ADR-0008): a visitor in one of these is not free (FR-QUE-063). */
    private static final Set<String> BUSY = Set.of("called", "serving", "held");

    /** Written on a system reaction to another ticket's transition, the way {@code EstimateEvents} and the scheduler already do. */
    private static final String SYSTEM = "system";

    /**
     * The event types §14.2's queue-side triggers fire on (ticket 38, FR-NTF-*): a reannounce or resume also writes
     * {@code ticket.called}/{@code ticket.serving} but leaves {@code fromState == toState} or isn't in this map, so
     * {@link #notifyTrigger} only ever fires on a real transition.
     */
    private static final Map<String, String> TRIGGER_BY_EVENT = Map.of(
            "ticket.issued", NotificationTriggerKeys.TICKET_ISSUED,
            "ticket.called", NotificationTriggerKeys.YOUR_TURN,
            "ticket.missed", NotificationTriggerKeys.MISSED_BACK_IN_QUEUE,
            "ticket.no_show", NotificationTriggerKeys.MARKED_NO_SHOW,
            "ticket.transferred", NotificationTriggerKeys.TICKET_TRANSFERRED,
            "ticket.completed", NotificationTriggerKeys.SERVICE_COMPLETED_FEEDBACK);

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final RealtimePublisher realtime;
    private final EstimateEvents estimates;
    /** Empty outside the {@code serving} profile (the notification pipeline is @Profile(SERVING)-only, ADR-0010); a
     * transition never has less to do for that (FR-NTF-003). */
    private final Optional<NotificationTrigger> notifications;

    TicketEvents(JdbcTemplate jdbc, JsonMapper mapper, RealtimePublisher realtime, EstimateEvents estimates, Optional<NotificationTrigger> notifications) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.realtime = realtime;
        this.estimates = estimates;
        this.notifications = notifications;
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
        reactToJourney(transition);
        return seq;
    }

    /**
     * FR-QUE-063: whichever channel just called a ticket, or freed one bound to a session, this keeps the invariant that a
     * Visit with more than one waiting Ticket never has two callable at once. A ticket that just left {@code waiting} for
     * {@code called} pauses every other {@code waiting} ticket of its Visit ({@link TicketTransition#PAUSE}); a ticket that
     * just left the busy states ({@code called}, {@code serving}, {@code held}) for anything else frees them again
     * ({@link TicketTransition#UNPAUSE}), unless another of the Visit's tickets is still busy (a Visit issued only ad hoc,
     * single-ticket tickets never has a sibling to pause, so this is a no-op for every ticket outside a Journey).
     */
    private void reactToJourney(Transition transition) {
        // A ticket's first event (issue) has no from-state; Set.of(...) throws on contains(null), so that is never busy.
        boolean wasBusy = transition.fromState() != null && BUSY.contains(transition.fromState());
        boolean isBusy = transition.toState() != null && BUSY.contains(transition.toState());
        if (wasBusy == isBusy) return;
        UUID visitId = jdbc.query("SELECT visit_id FROM ticket WHERE id = ?", (rs, i) -> rs.getObject("visit_id", UUID.class), transition.ticketId())
                .stream().findFirst().orElse(null);
        if (visitId == null) return;
        if (isBusy) pauseSiblings(visitId, transition);
        else unpauseSiblings(visitId, transition);
    }

    private void pauseSiblings(UUID visitId, Transition cause) {
        for (UUID sibling : siblingsIn(visitId, cause.ticketId(), TicketTransition.PAUSE.from())) {
            int updated = jdbc.update(
                    "UPDATE ticket SET state = ?, version = version + 1 WHERE id = ? AND state = ?", TicketTransition.PAUSE.to(), sibling, TicketTransition.PAUSE.from());
            if (updated != 1) continue;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("visit_id", visitId.toString());
            payload.put("cause_ticket_id", cause.ticketId().toString());
            append(new Transition(
                    sibling, TicketTransition.PAUSE.eventType(), TicketTransition.PAUSE.from(), TicketTransition.PAUSE.to(),
                    cause.actorId(), SYSTEM, null, payload, cause.deviceTime(), cause.serverTime()));
        }
    }

    private void unpauseSiblings(UUID visitId, Transition cause) {
        Boolean stillBusy = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM ticket WHERE visit_id = ? AND id <> ? AND state IN ('called', 'serving', 'held'))",
                Boolean.class, visitId, cause.ticketId());
        if (Boolean.TRUE.equals(stillBusy)) return;
        for (UUID sibling : siblingsIn(visitId, cause.ticketId(), TicketTransition.UNPAUSE.from())) {
            int updated = jdbc.update(
                    "UPDATE ticket SET state = ?, version = version + 1 WHERE id = ? AND state = ?", TicketTransition.UNPAUSE.to(), sibling, TicketTransition.UNPAUSE.from());
            if (updated != 1) continue;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("visit_id", visitId.toString());
            append(new Transition(
                    sibling, TicketTransition.UNPAUSE.eventType(), TicketTransition.UNPAUSE.from(), TicketTransition.UNPAUSE.to(),
                    cause.actorId(), SYSTEM, null, payload, cause.deviceTime(), cause.serverTime()));
        }
    }

    private List<UUID> siblingsIn(UUID visitId, UUID exceptTicketId, String state) {
        return jdbc.query(
                "SELECT id FROM ticket WHERE visit_id = ? AND id <> ? AND state = ? ORDER BY queued_at, id",
                (rs, i) -> rs.getObject("id", UUID.class), visitId, exceptTicketId, state);
    }

    /**
     * Tells the hub. The waiting count is read here, inside the transaction, so it is the count this transition leaves
     * behind; it travels with the event so a console can keep its number without asking again.
     */
    private void publish(Transition transition) {
        var facts = jdbc.queryForMap("SELECT service_id, token_number, site_id, visitor_id FROM ticket WHERE id = ?", transition.ticketId());
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
        // The visitor ticket page's own topic (ticket 37, §21.2, FR-MOB-013): every transition of the ticket, live.
        realtime.publish(Topics.ticket(transition.ticketId()), transition.eventType(), transition.deviceTime(), data);
        if (transition.counterId() != null) {
            realtime.publish(Topics.counter(transition.counterId()), transition.eventType(), transition.deviceTime(), data);
            UUID zoneId = jdbc.query("SELECT zone_id FROM counter WHERE id = ?", (rs, i) -> rs.getObject("zone_id", UUID.class), transition.counterId())
                    .stream().findFirst().orElse(null);
            if (zoneId != null) realtime.publish(Topics.zone(zoneId), transition.eventType(), transition.deviceTime(), data);
        }
        // A transition that moves the queue changes its estimate and the places of the tickets behind it (FR-QUE-042).
        estimates.transitioned(serviceId, transition.fromState(), transition.toState(), transition.deviceTime());
        notifyTrigger(transition, serviceId, (UUID) facts.get("site_id"), (UUID) facts.get("visitor_id"), (String) facts.get("token_number"));
    }

    /**
     * Fires a §14.2 queue-side trigger for this transition, if it maps to one (ticket 38, FR-NTF-003): a single fast
     * insert that joins this same transaction, never the notification's own send. A reannounce or resume writes the
     * same event type a real transition would ({@code ticket.called}, {@code ticket.serving}) but leaves
     * {@code fromState == toState}, so it never reaches the pipeline.
     */
    private void notifyTrigger(Transition transition, UUID serviceId, UUID siteId, UUID visitorId, String tokenNumber) {
        if (Objects.equals(transition.fromState(), transition.toState())) return;
        String triggerKey = TRIGGER_BY_EVENT.get(transition.eventType());
        if (triggerKey == null) return;
        notifications.ifPresent(n -> n.fire(triggerKey, new NotificationContext(siteId, serviceId, transition.ticketId(), visitorId, transition.counterId(), tokenNumber, transition.deviceTime())));
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
