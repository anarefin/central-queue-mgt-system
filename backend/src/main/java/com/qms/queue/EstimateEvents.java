package com.qms.queue;

import com.qms.platform.realtime.RealtimePublisher;
import com.qms.platform.realtime.Topics;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tells the realtime hub when a service's wait estimate moves (SRS §10.5, §21.4, FR-QUE-042); the estimate itself is
 * worked out by {@link WaitEstimates}.
 *
 * <p>Two events leave from here, both on the {@code queue:{service_id}} topic (SRS §21.2, §21.4). {@code queue.estimate_changed}
 * carries the figure a visitor joining now would be given, whenever the queue or the number of open counters changed.
 * {@code ticket.position_changed} carries a queued ticket's new place and its own estimate, for each ticket whose place moved.
 * They go on the queue topic because a hub topic per ticket is not evicted when idle; the visitor's own {@code ticket:} topic
 * arrives with the visitor ticket page. A ticket that is itself the subject of a {@code ticket.position_changed} transition
 * (returned to the queue, given another class) is announced by that event too; the one here adds its place and estimate.
 */
@Component
public class EstimateEvents {

    /** States in which a ticket is part of a queue, or is about to be. */
    private static final Set<String> QUEUED = Set.of("waiting", "paused", "remote");

    private final JdbcTemplate jdbc;
    private final QueueReads reads;
    private final WaitEstimates estimates;
    private final RealtimePublisher realtime;

    EstimateEvents(JdbcTemplate jdbc, QueueReads reads, WaitEstimates estimates, RealtimePublisher realtime) {
        this.jdbc = jdbc;
        this.reads = reads;
        this.estimates = estimates;
        this.realtime = realtime;
    }

    /**
     * A ticket of the service made a transition (called by {@code TicketEvents} in the transition's transaction). When it
     * joined or left the queue, or completed and so added a sample, the estimate is recomputed and published, and each ticket
     * whose place moved is told its new one.
     */
    void transitioned(UUID serviceId, String fromState, String toState, Instant at) {
        boolean queueMoved = queued(fromState) || queued(toState) || "completed".equals(toState);
        if (!queueMoved) return;
        List<QueueReads.Entry> entries = reads.ordered(serviceId, null).entries();
        WaitEstimates.Basis basis = estimates.basis(serviceId);
        publishEstimate(serviceId, entries.size(), basis, at);
        publishMovedPositions(serviceId, entries, basis, at);
    }

    /** {@code state} is null for a ticket's first event. */
    private static boolean queued(String state) {
        return state != null && QUEUED.contains(state);
    }

    /**
     * Counters opened or closed, or went on or off a break, for these services, so the estimate of each changed with no
     * ticket moving (FR-QUE-042).
     */
    public void capacityChanged(Collection<UUID> serviceIds, Instant at) {
        for (UUID serviceId : serviceIds) publishEstimate(serviceId, reads.waitingCount(serviceId), estimates.basis(serviceId), at);
    }

    private void publishEstimate(UUID serviceId, int waiting, WaitEstimates.Basis basis, Instant at) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service_id", serviceId.toString());
        data.put("waiting_count", waiting);
        data.put("open_counters", basis.openCounters());
        data.put("estimated_wait_minutes", basis.ahead(waiting));
        realtime.publish(Topics.queue(serviceId), "queue.estimate_changed", at, data);
    }

    /**
     * Compares each queued ticket's place with the one last announced and publishes those that differ. Writes go in one fixed
     * order, so two transitions of the same queue take the same rows in the same order and cannot wait on each other.
     */
    private void publishMovedPositions(UUID serviceId, List<QueueReads.Entry> entries, WaitEstimates.Basis basis, Instant at) {
        Map<UUID, Integer> announced = new HashMap<>();
        jdbc.query("SELECT ticket_id, position FROM ticket_position WHERE service_id = ?", rs -> {
            announced.put(rs.getObject("ticket_id", UUID.class), rs.getInt("position"));
        }, serviceId);

        Map<UUID, QueueReads.Entry> moved = new TreeMap<>();
        Map<UUID, QueueReads.Entry> queued = new HashMap<>();
        for (QueueReads.Entry entry : entries) {
            queued.put(entry.ticketId(), entry);
            if (!Integer.valueOf(entry.position()).equals(announced.get(entry.ticketId()))) moved.put(entry.ticketId(), entry);
        }
        Set<UUID> left = new TreeSet<>(announced.keySet());
        left.removeAll(queued.keySet());

        for (UUID ticketId : left) jdbc.update("DELETE FROM ticket_position WHERE ticket_id = ?", ticketId);
        for (QueueReads.Entry entry : moved.values()) {
            jdbc.update(
                    "INSERT INTO ticket_position (ticket_id, service_id, position) VALUES (?, ?, ?)"
                            + " ON CONFLICT (ticket_id) DO UPDATE SET service_id = EXCLUDED.service_id, position = EXCLUDED.position",
                    entry.ticketId(), serviceId, entry.position());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ticket_id", entry.ticketId().toString());
            data.put("token_number", entry.tokenNumber());
            data.put("service_id", serviceId.toString());
            data.put("position", entry.position());
            data.put("estimated_wait_minutes", basis.ahead(entry.position() - 1));
            realtime.publish(Topics.queue(serviceId), "ticket.position_changed", at, data);
        }
    }
}
