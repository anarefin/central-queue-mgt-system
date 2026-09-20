package com.qms.queue;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.notifications.NotificationContext;
import com.qms.platform.notifications.NotificationTrigger;
import com.qms.platform.notifications.NotificationTriggerKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The background side of a remote ticket's arrival (ticket 43, SRS §13.3, FR-MOB-020, FR-MOB-022): the approaching-turn
 * notification once a remote ticket nears the front of its queue, and the forfeit once one still remote at the very
 * front has been held past its Service's arrival deadline. Driven by {@link RemoteArrivalScheduler}; every node fires
 * the same sweep and each write here is a single guarded statement, so whichever node's write lands first is the one
 * that acts and the rest find nothing left to do (ADR-0010), the same pattern {@code AppointmentNoShowScheduler}
 * already uses for its own deadline sweep.
 *
 * <p>Reads {@code service_remote_rule} directly (its own two columns: {@code arrival_deadline_minutes} and
 * {@code forfeit_policy}), the same "read another context's table directly rather than depend on its package-private
 * repository" shape {@code com.qms.mobile.RemoteJoinRepository} already sets out for that same table.
 */
@Service
public class RemoteArrivalService {

    private final JdbcTemplate jdbc;
    private final QueueReads queues;
    private final WaitEstimates estimates;
    private final QueueProperties properties;
    private final TicketEvents events;
    private final AuditWriter audit;
    private final Clock clock;
    private final Optional<NotificationTrigger> notifications;

    RemoteArrivalService(
            JdbcTemplate jdbc,
            QueueReads queues,
            WaitEstimates estimates,
            QueueProperties properties,
            TicketEvents events,
            AuditWriter audit,
            Clock clock,
            Optional<NotificationTrigger> notifications) {
        this.jdbc = jdbc;
        this.queues = queues;
        this.estimates = estimates;
        this.properties = properties;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
        this.notifications = notifications;
    }

    private record RemotePolicy(int arrivalDeadlineMinutes, String forfeitPolicy) {}

    private RemotePolicy policyOf(UUID serviceId) {
        return jdbc.query(
                        "SELECT arrival_deadline_minutes, forfeit_policy FROM service_remote_rule WHERE service_id = ?",
                        (rs, i) -> new RemotePolicy(rs.getInt("arrival_deadline_minutes"), rs.getString("forfeit_policy")),
                        serviceId)
                .stream().findFirst().orElse(new RemotePolicy(15, "move_back"));
    }

    /**
     * FR-MOB-022, §19.1 {@code remote -> forfeited}: every Service with a remote ticket, once. Only the ticket
     * currently at the very front of its queue can be held or forfeited; any other remote ticket that had a hold clock
     * running loses it here, the same self-healing read every tick already gives {@link #sweepApproachingTurn}.
     */
    @Transactional
    public int sweepForfeits() {
        Instant now = clock.instant();
        int count = 0;
        for (UUID serviceId : servicesWithRemoteTickets()) count += sweepForfeitsOf(serviceId, now);
        return count;
    }

    private List<UUID> servicesWithRemoteTickets() {
        return jdbc.queryForList("SELECT DISTINCT service_id FROM ticket WHERE state = 'remote'", UUID.class);
    }

    private int sweepForfeitsOf(UUID serviceId, Instant now) {
        List<QueueReads.Entry> entries = queues.ordered(serviceId, null).entries();
        if (entries.isEmpty()) return 0;
        QueueReads.Entry head = entries.get(0);
        // A remote ticket that once held the front but has since been overtaken (an escalated ticket, a reprioritise)
        // is no longer being held: its clock resets, so it gets a fresh deadline once it next reaches the front.
        jdbc.update(
                "UPDATE ticket SET remote_hold_started_at = NULL WHERE service_id = ? AND state = 'remote' AND id <> ? AND remote_hold_started_at IS NOT NULL",
                serviceId, head.ticketId());
        if (!"remote".equals(head.state())) return 0;

        List<OffsetDateTime> heldSinceRows = jdbc.query(
                "SELECT remote_hold_started_at FROM ticket WHERE id = ?", (rs, i) -> rs.getObject("remote_hold_started_at", OffsetDateTime.class), head.ticketId());
        OffsetDateTime heldSinceRow = heldSinceRows.isEmpty() ? null : heldSinceRows.get(0);
        Instant heldSince = heldSinceRow == null ? null : heldSinceRow.toInstant();

        RemotePolicy policy = policyOf(serviceId);
        if (heldSince == null) {
            jdbc.update("UPDATE ticket SET remote_hold_started_at = ? WHERE id = ? AND state = 'remote' AND remote_hold_started_at IS NULL", ts(now), head.ticketId());
            return 0;
        }
        if (now.isBefore(heldSince.plus(Duration.ofMinutes(policy.arrivalDeadlineMinutes())))) return 0;
        return applyForfeit(head.ticketId(), serviceId, policy, now) ? 1 : 0;
    }

    private boolean applyForfeit(UUID ticketId, UUID serviceId, RemotePolicy policy, Instant now) {
        if ("cancel".equals(policy.forfeitPolicy())) {
            boolean updated = jdbc.update(
                            "UPDATE ticket SET state = ?, remote_hold_started_at = NULL, version = version + 1 WHERE id = ? AND state = 'remote'",
                            TicketTransition.FORFEIT.to(), ticketId)
                    == 1;
            if (!updated) return false;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reason", "arrival_deadline_elapsed");
            payload.put("forfeit_policy", "cancel");
            events.append(new TicketEvents.Transition(
                    ticketId, TicketTransition.FORFEIT.eventType(), TicketTransition.FORFEIT.from(), TicketTransition.FORFEIT.to(), null, "system", null, payload, now, now));
            audit.record(AuditEvent.of(TicketTransition.FORFEIT.eventType(), "ticket", ticketId)
                    .withBefore(Map.of("state", "remote"))
                    .withAfter(Map.of("state", TicketTransition.FORFEIT.to()))
                    .withReason("arrival_deadline_elapsed"));
            return true;
        }
        int adjustment = queues.moveBackAdjustment(ticketId, properties.remoteForfeitBackPlaces());
        boolean updated = jdbc.update(
                        "UPDATE ticket SET score_adjustment_minutes = ?, remote_hold_started_at = NULL, version = version + 1 WHERE id = ? AND state = 'remote'",
                        adjustment, ticketId)
                == 1;
        if (!updated) return false;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", "arrival_deadline_elapsed");
        payload.put("forfeit_policy", "move_back");
        payload.put("score_adjustment_minutes", adjustment);
        events.append(new TicketEvents.Transition(
                ticketId,
                TicketTransition.FORFEIT_MOVED_BACK.eventType(),
                TicketTransition.FORFEIT_MOVED_BACK.from(),
                TicketTransition.FORFEIT_MOVED_BACK.to(),
                null,
                "system",
                null,
                payload,
                now,
                now));
        audit.record(AuditEvent.of(TicketTransition.FORFEIT_MOVED_BACK.eventType(), "ticket", ticketId)
                .withBefore(Map.of("state", "remote"))
                .withAfter(Map.of("score_adjustment_minutes", adjustment))
                .withReason("arrival_deadline_elapsed"));
        return true;
    }

    /**
     * FR-MOB-020: every remote ticket not yet notified, once it is at or inside the configured threshold of places from
     * the front, or its estimated wait is at or under the configured threshold of minutes — whichever comes first.
     * Claims the row before firing, so several nodes racing the same sweep send it at most once.
     */
    @Transactional
    public int sweepApproachingTurn() {
        Instant now = clock.instant();
        int count = 0;
        for (UUID ticketId : jdbc.queryForList("SELECT id FROM ticket WHERE state = 'remote' AND approaching_turn_notified_at IS NULL", UUID.class)) {
            if (fireApproachingTurn(ticketId, now)) count++;
        }
        return count;
    }

    private boolean fireApproachingTurn(UUID ticketId, Instant now) {
        Map<String, Object> row = jdbc.queryForMap("SELECT service_id, site_id, visitor_id, token_number FROM ticket WHERE id = ?", ticketId);
        UUID serviceId = (UUID) row.get("service_id");
        Integer position = queues.positionOf(ticketId);
        if (position == null) return false;
        WaitEstimate estimate = estimates.atPosition(serviceId, position);
        boolean nearEnoughByPosition = position <= properties.approachingTurnThresholdTickets();
        boolean nearEnoughByEstimate = estimate != null && estimate.low() <= properties.approachingTurnThresholdMinutes();
        if (!nearEnoughByPosition && !nearEnoughByEstimate) return false;
        boolean claimed = jdbc.update("UPDATE ticket SET approaching_turn_notified_at = ? WHERE id = ? AND approaching_turn_notified_at IS NULL", ts(now), ticketId) == 1;
        if (!claimed) return false;
        notifications.ifPresent(n -> n.fire(
                NotificationTriggerKeys.APPROACHING_TURN,
                new NotificationContext(
                        (UUID) row.get("site_id"), serviceId, ticketId, (UUID) row.get("visitor_id"), null, (String) row.get("token_number"), now, null, null)));
        return true;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
