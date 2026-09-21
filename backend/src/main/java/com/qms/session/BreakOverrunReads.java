package com.qms.session;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link BreakOverrunScheduler}'s own read (SRS §11.3, FR-AGT-023, ticket 47). */
@Repository
class BreakOverrunReads {

    record OpenBreak(UUID counterSessionId, UUID siteId, Instant startedAt, int maxMinutes) {}

    private final JdbcTemplate jdbc;

    BreakOverrunReads(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every break still running (FR-AGT-021: one live break per session at most) whose type has a maximum
     * duration (FR-AGT-020); a type with none can never overrun. */
    List<OpenBreak> openWithMaximum() {
        return jdbc.query(
                "SELECT br.counter_session_id, z.site_id, br.started_at, bt.max_minutes"
                        + " FROM break_record br"
                        + " JOIN break_type bt ON bt.id = br.break_type_id"
                        + " JOIN counter_session cs ON cs.id = br.counter_session_id"
                        + " JOIN counter c ON c.id = cs.counter_id"
                        + " JOIN zone z ON z.id = c.zone_id"
                        + " WHERE br.ended_at IS NULL AND bt.max_minutes IS NOT NULL",
                (rs, i) -> new OpenBreak(
                        rs.getObject("counter_session_id", UUID.class), rs.getObject("site_id", UUID.class),
                        rs.getTimestamp("started_at").toInstant(), rs.getInt("max_minutes")));
    }
}
