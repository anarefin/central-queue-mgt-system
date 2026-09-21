package com.qms.dashboard;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AlertRepository {

    private final JdbcTemplate jdbc;

    AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The most recent still-open alert of this key, if its last breach was within {@code since} (FR-MON-023). Both
     * {@code serviceId} and {@code subjectId} may be null, so they are matched with {@code IS NOT DISTINCT FROM}. */
    Optional<Alert> findGroupable(UUID siteId, UUID serviceId, String thresholdType, UUID subjectId, Instant since) {
        return jdbc.query(
                        "SELECT * FROM alert WHERE site_id = ? AND service_id IS NOT DISTINCT FROM ? AND threshold_type = ?"
                                + " AND subject_id IS NOT DISTINCT FROM ? AND state = 'open' AND last_breached_at >= ?"
                                + " ORDER BY last_breached_at DESC LIMIT 1",
                        this::map, siteId, serviceId, thresholdType, subjectId, ts(since))
                .stream()
                .findFirst();
    }

    UUID insert(
            UUID siteId, UUID serviceId, String thresholdType, UUID subjectId, BigDecimal measuredValue, BigDecimal thresholdValue, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO alert (id, site_id, service_id, threshold_type, subject_id, state, breach_count, measured_value, threshold_value,"
                        + " first_breached_at, last_breached_at, created_at) VALUES (?, ?, ?, ?, ?, 'open', 1, ?, ?, ?, ?, ?)",
                id, siteId, serviceId, thresholdType, subjectId, measuredValue, thresholdValue, ts(now), ts(now), ts(now));
        return id;
    }

    void recordBreach(UUID id, Instant now, BigDecimal measuredValue) {
        jdbc.update("UPDATE alert SET breach_count = breach_count + 1, last_breached_at = ?, measured_value = ? WHERE id = ?", ts(now), measuredValue, id);
    }

    void acknowledge(UUID id, Instant now, UUID actorId, String note) {
        jdbc.update(
                "UPDATE alert SET state = 'acknowledged', acknowledged_at = ?, acknowledged_by = ?, acknowledgement_note = ? WHERE id = ?",
                ts(now), actorId, note, id);
    }

    void escalate(UUID id, Instant now) {
        jdbc.update("UPDATE alert SET escalated_at = ? WHERE id = ?", ts(now), id);
    }

    Optional<Alert> find(UUID id) {
        return jdbc.query("SELECT * FROM alert WHERE id = ?", this::map, id).stream().findFirst();
    }

    List<Alert> forSite(UUID siteId, String state) {
        return state == null
                ? jdbc.query("SELECT * FROM alert WHERE site_id = ? ORDER BY created_at DESC", this::map, siteId)
                : jdbc.query("SELECT * FROM alert WHERE site_id = ? AND state = ? ORDER BY created_at DESC", this::map, siteId, state);
    }

    /** Open, unescalated alerts whose Service overrides {@code escalation_delay_minutes} (join needed since the
     * delay itself may vary by Service); {@link AlertEscalationScheduler} filters {@code break_overrun} (no
     * Service) against {@link AlertProperties}' own default separately. */
    List<Alert> dueForServiceEscalation(Instant now) {
        return jdbc.query(
                "SELECT a.* FROM alert a JOIN service_alert_threshold t ON t.service_id = a.service_id"
                        + " WHERE a.state = 'open' AND a.escalated_at IS NULL AND t.escalation_delay_minutes IS NOT NULL"
                        + " AND t.escalation_delay_minutes > 0 AND a.first_breached_at <= ? - make_interval(mins => t.escalation_delay_minutes)",
                this::map, ts(now));
    }

    /** Open, unescalated alerts with no Service of their own ({@code break_overrun}), or whose Service has no
     * escalation-delay override, due against {@link AlertProperties}' own default. */
    List<Alert> dueForDefaultEscalation(Instant now, int defaultDelayMinutes) {
        return jdbc.query(
                "SELECT a.* FROM alert a LEFT JOIN service_alert_threshold t ON t.service_id = a.service_id"
                        + " WHERE a.state = 'open' AND a.escalated_at IS NULL AND (t.service_id IS NULL OR t.escalation_delay_minutes IS NULL)"
                        + " AND a.first_breached_at <= ? - make_interval(mins => ?)",
                this::map, ts(now), defaultDelayMinutes);
    }

    private Alert map(ResultSet rs, int rowNum) throws SQLException {
        return new Alert(
                rs.getObject("id", UUID.class),
                rs.getObject("site_id", UUID.class),
                rs.getObject("service_id", UUID.class),
                rs.getString("threshold_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getString("state"),
                rs.getInt("breach_count"),
                rs.getBigDecimal("measured_value"),
                rs.getBigDecimal("threshold_value"),
                instant(rs, "first_breached_at"),
                instant(rs, "last_breached_at"),
                instant(rs, "escalated_at"),
                instant(rs, "acknowledged_at"),
                rs.getObject("acknowledged_by", UUID.class),
                rs.getString("acknowledgement_note"),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
