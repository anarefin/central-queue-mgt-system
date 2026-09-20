package com.qms.mobile;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The one read this package needs of a Service's remote-join policy (ticket 42, FR-MOB-011), for the join screen to
 * show before a visitor commits (FR-MOB-023): plain SQL against {@code service_remote_rule}, the table
 * {@code com.qms.issuance} owns and administers, the same "read another context's table directly rather than depend
 * on its package-private repository" shape this package's own doc comment already sets out. Enforcement of every one
 * of these figures happens again, authoritatively, inside {@code IssuanceService#issueRemote} when the visitor
 * actually joins; a stale read here only ever affects what is shown a moment before that.
 */
@Repository
class RemoteJoinRepository {

    /** A Service's remote-join policy, or the "off" default when no admin has configured one. */
    record Policy(boolean virtualQueueEnabled, Integer maxDistanceMeters, int maxRemoteSharePct, int joinWindowMinutes, int arrivalDeadlineMinutes) {
        static final Policy NONE = new Policy(false, null, 40, 30, 15);
    }

    private final JdbcTemplate jdbc;

    RemoteJoinRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean serviceExists(UUID serviceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM service WHERE id = ?)", Boolean.class, serviceId));
    }

    Policy policyOf(UUID serviceId) {
        return jdbc.query(
                        "SELECT virtual_queue_enabled, max_distance_m, max_remote_share_pct, join_window_minutes, arrival_deadline_minutes"
                                + " FROM service_remote_rule WHERE service_id = ?",
                        (rs, i) -> new Policy(
                                rs.getBoolean("virtual_queue_enabled"),
                                rs.getObject("max_distance_m", Integer.class),
                                rs.getInt("max_remote_share_pct"),
                                rs.getInt("join_window_minutes"),
                                rs.getInt("arrival_deadline_minutes")),
                        serviceId)
                .stream().findFirst().orElse(Policy.NONE);
    }
}
