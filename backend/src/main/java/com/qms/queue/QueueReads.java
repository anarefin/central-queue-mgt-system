package com.qms.queue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads of a service's queue at one site. A queue is logical (FR-QUE-001): it is the set of {@code waiting} and
 * {@code paused} tickets of a service, read through the partial index on (service, state). Until the scoring engine
 * arrives the order is first come first served, by {@code queued_at} and then id.
 */
@Repository
public class QueueReads {

    /** A ticket in a queue with its 1-based place. */
    public record Entry(UUID ticketId, String tokenNumber, String state, String originChannel, Instant queuedAt, int position) {}

    private final JdbcTemplate jdbc;

    QueueReads(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int waitingCount(UUID serviceId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM ticket WHERE service_id = ? AND state IN ('waiting', 'paused')", Integer.class, serviceId);
        return count == null ? 0 : count;
    }

    /** The place of a queued ticket, or null once it has left the queue. */
    public Integer positionOf(UUID ticketId) {
        List<Integer> rows = jdbc.query(
                "SELECT (SELECT count(*) FROM ticket o WHERE o.service_id = t.service_id AND o.state IN ('waiting', 'paused')"
                        + " AND (o.queued_at, o.id) <= (t.queued_at, t.id)) AS position"
                        + " FROM ticket t WHERE t.id = ? AND t.state IN ('waiting', 'paused')",
                (rs, i) -> rs.getInt("position"),
                ticketId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The first {@code limit} tickets of the queue, in order. */
    public List<Entry> next(UUID serviceId, int limit) {
        return jdbc.query(
                "SELECT id, token_number, state, origin_channel, queued_at, row_number() OVER (ORDER BY queued_at, id) AS position"
                        + " FROM ticket WHERE service_id = ? AND state IN ('waiting', 'paused') ORDER BY queued_at, id LIMIT ?",
                (rs, i) -> new Entry(
                        rs.getObject("id", UUID.class),
                        rs.getString("token_number"),
                        rs.getString("state"),
                        rs.getString("origin_channel"),
                        rs.getObject("queued_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getInt("position")),
                serviceId,
                limit);
    }
}
