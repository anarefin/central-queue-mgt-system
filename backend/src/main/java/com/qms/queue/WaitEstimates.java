package com.qms.queue;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What a wait estimate is made from, read for one service (SRS §10.5, FR-QUE-040, FR-QUE-041).
 *
 * <p>Nothing is stored about an estimate: it is worked out from the queue, the open counters and the recent handling times
 * every time it is asked for (FR-QUE-042), so a REST read and a published event ({@link EstimateEvents}) agree. This class
 * only reads, so a topic source can use it without depending on the hub it publishes to.
 */
@Component
public class WaitEstimates {

    /** What the formula needs for one service: the counters open for it and the handling time in minutes. */
    public record Basis(int openCounters, double handlingMinutes) {

        /** The range for a ticket with {@code ticketsAhead} in front of it. */
        public WaitEstimate ahead(int ticketsAhead) {
            return WaitEstimator.estimate(ticketsAhead, openCounters, handlingMinutes);
        }
    }

    private final JdbcTemplate jdbc;

    WaitEstimates(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Counters that can take a ticket of the service now: those with a session that is {@code open} (not on a break or
     * closing) and has chosen this service. The average is over the last {@link WaitEstimator#WINDOW} completed tickets.
     */
    public Basis basis(UUID serviceId) {
        Integer open = jdbc.queryForObject("SELECT count(*) FROM counter_session WHERE state = 'open' AND ? = ANY (services)", Integer.class, serviceId);
        List<Integer> recent = jdbc.queryForList(
                "SELECT service_seconds FROM ticket WHERE service_id = ? AND state = 'completed' AND service_seconds IS NOT NULL"
                        + " ORDER BY closed_at DESC, id LIMIT " + WaitEstimator.WINDOW,
                Integer.class,
                serviceId);
        Integer expected = jdbc.queryForObject("SELECT expected_minutes FROM service WHERE id = ?", Integer.class, serviceId);
        return new Basis(open == null ? 0 : open, WaitEstimator.handlingMinutes(recent, expected == null ? 0 : expected));
    }

    /** The range for a ticket with {@code ticketsAhead} in front of it in the service's queue. */
    public WaitEstimate ahead(UUID serviceId, int ticketsAhead) {
        return basis(serviceId).ahead(ticketsAhead);
    }

    /** The range for the ticket at {@code position} (1 is next), or null once it has left the queue. */
    public WaitEstimate atPosition(UUID serviceId, Integer position) {
        return position == null ? null : ahead(serviceId, position - 1);
    }
}
