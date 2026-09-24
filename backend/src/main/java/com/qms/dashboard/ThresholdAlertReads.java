package com.qms.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The threshold sweep's own SQL (SRS §15.4, FR-MON-020, ticket 47), each metric scoped straight to one Service's own
 * queue and counters — the same "read another context's table directly" shape {@link DashboardReads} already sets
 * for the live dashboard. {@code device_offline_minutes} is the one exception: a Device belongs to a Site and Zone,
 * never a Service (§18.2), so it is evaluated Site-wide; a Service's own threshold row is still honoured, meaning
 * two Services at the same Site that both configure it raise their own, separate alerts for the same underlying
 * device.
 */
@Repository
class ThresholdAlertReads {

    private final JdbcTemplate jdbc;

    ThresholdAlertReads(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record ServiceSite(UUID siteId, ZoneId timezone) {}

    /** Null once the Service (or its Site) no longer exists, so a sweep started before a deletion skips it cleanly. */
    ServiceSite site(UUID serviceId) {
        return jdbc.query(
                        "SELECT s.id AS site_id, s.timezone FROM service sv JOIN service_group g ON g.id = sv.service_group_id JOIN site s ON s.id = g.site_id WHERE sv.id = ?",
                        (rs, i) -> new ServiceSite(rs.getObject("site_id", UUID.class), ZoneId.of(rs.getString("timezone"))),
                        serviceId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    int queueLength(UUID serviceId) {
        Integer n = jdbc.query("SELECT count(*) FROM ticket WHERE service_id = ? AND state IN ('waiting', 'paused', 'remote')", rs -> rs.next() ? rs.getInt(1) : 0, serviceId);
        return n == null ? 0 : n;
    }

    long longestWaitSeconds(UUID serviceId, Instant now) {
        Double seconds = jdbc.query(
                "SELECT max(extract(epoch FROM (?::timestamptz - queued_at))) FROM ticket WHERE service_id = ? AND state IN ('waiting', 'paused', 'remote')",
                rs -> rs.next() ? (rs.getBigDecimal(1) == null ? null : rs.getBigDecimal(1).doubleValue()) : null,
                java.sql.Timestamp.from(now), serviceId);
        return seconds == null ? 0L : seconds.longValue();
    }

    /** A counter offering this Service, with no live session, while this Service's own queue is non-empty
     * (FR-QUE-033's load-balancing signal, narrowed to one Service). */
    int idleCountersWithQueue(UUID serviceId) {
        Integer n = jdbc.query(
                "SELECT count(DISTINCT c.id) FROM counter c JOIN counter_service link ON link.counter_id = c.id"
                        + " WHERE c.active = true AND link.service_id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM counter_session cs WHERE cs.counter_id = c.id AND cs.state IN ('open', 'on_break', 'closing'))"
                        + " AND EXISTS (SELECT 1 FROM ticket t WHERE t.service_id = ? AND t.state IN ('waiting', 'paused', 'remote'))",
                rs -> rs.next() ? rs.getInt(1) : 0,
                serviceId, serviceId);
        return n == null ? 0 : n;
    }

    /** No-show ÷ (no-show + served) as a percentage, since {@code todayStart} (the Site's own local day, like
     * {@link DashboardReads#throughputToday}); null when neither has happened yet today, so a quiet Service never
     * false-positives on a zero-over-zero rate. */
    BigDecimal noShowRatePercent(UUID serviceId, Instant todayStart) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query(
                "SELECT te.event_type, count(*) AS cnt FROM ticket_event te JOIN ticket t ON t.id = te.ticket_id"
                        + " WHERE te.event_type IN ('ticket.completed', 'ticket.no_show') AND te.occurred_at >= ? AND t.service_id = ? GROUP BY te.event_type",
                rs -> {
                    counts.put(rs.getString("event_type"), rs.getInt("cnt"));
                },
                java.sql.Timestamp.from(todayStart), serviceId);
        int served = counts.getOrDefault("ticket.completed", 0);
        int noShow = counts.getOrDefault("ticket.no_show", 0);
        int denominator = served + noShow;
        if (denominator == 0) return null;
        return BigDecimal.valueOf(noShow).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    /** The longest any active kiosk or display at this Site has gone without a heartbeat, in minutes; a device never
     * heard from at all counts from when it was paired. 0 when the Site has no active device. */
    long maxDeviceOfflineMinutes(UUID siteId, Instant now) {
        Double seconds = jdbc.query(
                "SELECT max(extract(epoch FROM (?::timestamptz - coalesce(last_heartbeat_at, paired_at)))) FROM device WHERE site_id = ? AND active = true",
                rs -> rs.next() ? (rs.getBigDecimal(1) == null ? null : rs.getBigDecimal(1).doubleValue()) : null,
                java.sql.Timestamp.from(now), siteId);
        return seconds == null ? 0L : seconds.longValue() / 60;
    }
}
