package com.qms.reporting;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * The read path of ticket 50's six operational reports (SRS §16.1, §15.2, §15.3): the visitor flow, counter, agent,
 * service, department and site reports. Every ticket-grained metric is read from {@code reporting.ticket_fact}
 * alone (never the live {@code ticket} table, the same rule {@link DetailedTokenReportReads} already follows); the
 * counter and agent reports also read the live {@code counter_session}/{@code break_record} tables directly for
 * session and break time, the same "read another context's table directly for a reporting-style view" shape {@code
 * com.qms.session.BreakReportService}/{@code SessionRepository} already sets for the break report (ticket 16) and
 * {@code com.qms.dashboard.DashboardReads} sets for the live dashboard — these two tables are staff-occupancy
 * records, orders of magnitude smaller than the ticket stream {@code reporting.ticket_fact} exists to protect
 * (§16's own "a heavy annual export cannot slow down a queue").
 *
 * <p>Percentiles (P90 wait) are computed by PostgreSQL's own {@code percentile_cont} directly over the raw rows a
 * filter reaches, never derived from an already-grouped average (FR-MON-010). Every average, sum and percentile
 * used as a comparison total is likewise read with its own ungrouped query, not summed or averaged from the grouped
 * per-row results in Java, for the same reason.
 */
@Repository
class OperationalReportReads {

    private final JdbcTemplate jdbc;

    OperationalReportReads(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- visitor flow (§16.1: Hour/day/month/year -> Issued, served, cancelled, no-show, peak concurrent waiting) ----

    List<Map<String, Object>> visitorFlowRows(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups, String grain) {
        List<Map<String, Object>> rows = counts(filter, allowedSites, allowedGroups, "date_trunc('" + grain + "', tf.issued_at)", "bucket", true);
        Map<Object, Long> peaks = peakConcurrency(filter, allowedSites, allowedGroups, "date_trunc('" + grain + "', tf.issued_at)");
        for (Map<String, Object> row : rows) row.put("peak_concurrent_waiting", peaks.getOrDefault(row.get("bucket"), 0L));
        return rows;
    }

    Map<String, Object> visitorFlowTotals(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        Map<String, Object> row = counts(filter, allowedSites, allowedGroups, "true", "bucket", false).stream().findFirst().orElseGet(LinkedHashMap::new);
        row.remove("bucket");
        Map<Object, Long> peaks = peakConcurrency(filter, allowedSites, allowedGroups, "true");
        row.put("peak_concurrent_waiting", peaks.getOrDefault(true, 0L));
        // Abandonment rate (§15.3: "Cancelled or no-show ÷ issued"), derived here from the same row's own counts
        // rather than a second query — the division is safe because both sides already come from one aggregate.
        long issued = ((Number) row.getOrDefault("issued", 0L)).longValue();
        long cancelled = ((Number) row.getOrDefault("cancelled", 0L)).longValue();
        long noShow = ((Number) row.getOrDefault("no_show", 0L)).longValue();
        row.put("abandonment_rate_pct", issued > 0 ? round2((cancelled + noShow) * 100.0 / issued) : null);
        return row;
    }

    /** Channel mix (§15.3: "Share of tickets by origin channel"), returned alongside the visitor flow report only. */
    List<Map<String, Object>> channelMix(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder("SELECT tf.channel, count(*) AS ticket_count FROM reporting.ticket_fact tf WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" GROUP BY tf.channel ORDER BY tf.channel");
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channel", rs.getString("channel"));
            row.put("count", rs.getLong("ticket_count"));
            return row;
        }, args.toArray());
    }

    private List<Map<String, Object>> counts(
            DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups, String bucketExpr, String bucketAlias, boolean group) {
        StringBuilder sql = new StringBuilder("SELECT " + bucketExpr + " AS " + bucketAlias
                + ", count(*) FILTER (WHERE tf.is_chain_head) AS issued"
                + ", count(*) FILTER (WHERE tf.state = 'completed') AS served"
                + ", count(*) FILTER (WHERE tf.state = 'cancelled') AS cancelled"
                + ", count(*) FILTER (WHERE tf.state = 'no_show') AS no_show"
                + " FROM reporting.ticket_fact tf WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, filter, allowedSites, allowedGroups);
        if (group) sql.append(" GROUP BY 1 ORDER BY 1");
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(bucketAlias, bucketAlias.equals("bucket") && group ? instant(rs.getTimestamp(bucketAlias)) : true);
            row.put("issued", rs.getLong("issued"));
            row.put("served", rs.getLong("served"));
            row.put("cancelled", rs.getLong("cancelled"));
            row.put("no_show", rs.getLong("no_show"));
            return row;
        }, args.toArray());
    }

    /**
     * The peak number of tickets waiting at once, per bucket (§15.1's own "Longest waits" concern applied over
     * time): a classic sweep line over each ticket's own waiting interval, {@code [issued_at, ...)} to whichever of
     * called/closed/refreshed comes first — a ticket still waiting at refresh time has no end yet, so it is treated
     * as waiting through "now" (the refresh snapshot), not zeroed out. A ticket is attributed to the bucket its own
     * {@code issued_at} falls in, even if its wait crosses into the next bucket, so a period's total peak is a
     * lower bound on the true continuous peak — a documented approximation, not a continuous-time reconstruction
     * (which would need the live {@code ticket_event} log this schema deliberately keeps a report away from).
     */
    private Map<Object, Long> peakConcurrency(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups, String bucketExpr) {
        StringBuilder sql = new StringBuilder(
                "WITH events AS ("
                        + " SELECT " + bucketExpr + " AS bucket, tf.issued_at AS ts, 1 AS delta FROM reporting.ticket_fact tf WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" UNION ALL SELECT " + bucketExpr + " AS bucket, COALESCE(tf.called_at, tf.closed_at, tf.refreshed_at) AS ts, -1 AS delta"
                + " FROM reporting.ticket_fact tf WHERE 1 = 1");
        appendFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append("), running AS ("
                + " SELECT bucket, SUM(delta) OVER (PARTITION BY bucket ORDER BY ts, delta DESC ROWS UNBOUNDED PRECEDING) AS concurrent FROM events)"
                + " SELECT bucket, MAX(concurrent) AS peak FROM running GROUP BY bucket");
        Map<Object, Long> peaks = new LinkedHashMap<>();
        jdbc.query(sql.toString(), (RowCallbackHandler) rs -> {
            Object key = "true".equals(bucketExpr) ? Boolean.TRUE : instant(rs.getTimestamp("bucket"));
            long peak = rs.getLong("peak");
            peaks.put(key, Math.max(0, peak));
        }, args.toArray());
        return peaks;
    }

    // ---- counter report (§16.1: Counter x period -> sessions, open hours, served, idle time, utilisation) --------

    List<Map<String, Object>> counterRows(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups, Instant now) {
        Map<UUID, Map<String, Object>> byCounter = new LinkedHashMap<>();
        for (Map<String, Object> row : counterSessions(filter, allowedSites, allowedGroups, now, "cs.counter_id")) {
            byCounter.put((UUID) row.get("counter_id"), row);
        }
        mergeServed(byCounter, "counter_id", filter, allowedSites, allowedGroups);
        mergeBreakSeconds(byCounter, "cs.counter_id AS key_id", filter, allowedSites, allowedGroups);
        byCounter.values().forEach(OperationalReportReads::finishCounterRow);
        return List.copyOf(byCounter.values());
    }

    Map<String, Object> counterTotals(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups, Instant now) {
        List<Map<String, Object>> whole = counterSessions(filter, allowedSites, allowedGroups, now, "true");
        Map<String, Object> row = whole.isEmpty() ? new LinkedHashMap<>() : whole.get(0);
        row.remove("counter_id");
        row.remove("counter_label");
        row.remove("zone_id");
        row.remove("zone_name");
        row.remove("site_id");
        row.remove("site_name");
        mergeServedTotal(row, filter, allowedSites, allowedGroups);
        mergeBreakSecondsTotal(row, filter, allowedSites, allowedGroups);
        finishCounterRow(row);
        return row;
    }

    private List<Map<String, Object>> counterSessions(
            DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups, Instant now, String groupKey) {
        boolean grouped = !"true".equals(groupKey);
        StringBuilder sql = new StringBuilder(
                "SELECT " + (grouped ? "cs.counter_id, c.label AS counter_label, c.zone_id, z.name AS zone_name, z.site_id, s.name AS site_name," : "")
                        + " count(*) AS sessions,"
                        + " COALESCE(SUM(EXTRACT(EPOCH FROM (COALESCE(cs.closed_at, ?) - cs.opened_at))), 0) AS open_seconds"
                        + " FROM counter_session cs"
                        + " JOIN counter c ON c.id = cs.counter_id"
                        + " JOIN zone z ON z.id = c.zone_id"
                        + " JOIN site s ON s.id = z.site_id"
                        + " WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(now));
        appendSessionRange(sql, args, filter);
        appendSessionScope(sql, args, filter, allowedSites, allowedGroups);
        if (grouped) sql.append(" GROUP BY cs.counter_id, c.label, c.zone_id, z.name, z.site_id, s.name");
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            if (grouped) {
                row.put("counter_id", rs.getObject("counter_id", UUID.class));
                row.put("counter_label", rs.getString("counter_label"));
                row.put("zone_id", rs.getObject("zone_id", UUID.class));
                row.put("zone_name", rs.getString("zone_name"));
                row.put("site_id", rs.getObject("site_id", UUID.class));
                row.put("site_name", rs.getString("site_name"));
            }
            row.put("sessions", rs.getLong("sessions"));
            row.put("open_seconds", rs.getLong("open_seconds"));
            return row;
        }, args.toArray());
    }

    private void mergeServed(Map<UUID, Map<String, Object>> byKey, String keyColumn, DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder(
                "SELECT tf." + keyColumn + " AS key_id, count(*) FILTER (WHERE tf.state = 'completed') AS served,"
                        + " COALESCE(SUM(tf.service_seconds) FILTER (WHERE tf.state = 'completed'), 0) AS serving_seconds"
                        + " FROM reporting.ticket_fact tf WHERE tf." + keyColumn + " IS NOT NULL");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" GROUP BY tf.").append(keyColumn);
        jdbc.query(sql.toString(), (RowCallbackHandler) rs -> {
            UUID key = rs.getObject("key_id", UUID.class);
            Map<String, Object> row = byKey.get(key);
            if (row == null) return;
            row.put("served", rs.getLong("served"));
            row.put("serving_seconds", rs.getLong("serving_seconds"));
        }, args.toArray());
    }

    private void mergeServedTotal(Map<String, Object> row, DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FILTER (WHERE tf.state = 'completed') AS served,"
                        + " COALESCE(SUM(tf.service_seconds) FILTER (WHERE tf.state = 'completed'), 0) AS serving_seconds"
                        + " FROM reporting.ticket_fact tf WHERE tf.counter_id IS NOT NULL");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, filter, allowedSites, allowedGroups);
        jdbc.query(sql.toString(), (RowCallbackHandler) rs -> {
            row.put("served", rs.getLong("served"));
            row.put("serving_seconds", rs.getLong("serving_seconds"));
        }, args.toArray());
    }

    private void mergeBreakSeconds(Map<UUID, Map<String, Object>> byKey, String selectKey, DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + selectKey + ", COALESCE(SUM(EXTRACT(EPOCH FROM (br.ended_at - br.started_at))), 0) AS break_seconds"
                        + " FROM break_record br"
                        + " JOIN counter_session cs ON cs.id = br.counter_session_id"
                        + " JOIN counter c ON c.id = cs.counter_id"
                        + " JOIN zone z ON z.id = c.zone_id"
                        + " WHERE br.ended_at IS NOT NULL");
        List<Object> args = new ArrayList<>();
        appendSessionRange(sql, args, filter);
        appendSessionScope(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" GROUP BY ").append(selectKey.replace(" AS key_id", ""));
        jdbc.query(sql.toString(), (RowCallbackHandler) rs -> {
            UUID key = rs.getObject("key_id", UUID.class);
            Map<String, Object> row = byKey.get(key);
            if (row != null) row.put("break_seconds", rs.getLong("break_seconds"));
        }, args.toArray());
    }

    private void mergeBreakSecondsTotal(Map<String, Object> row, DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (br.ended_at - br.started_at))), 0) AS break_seconds"
                        + " FROM break_record br"
                        + " JOIN counter_session cs ON cs.id = br.counter_session_id"
                        + " JOIN counter c ON c.id = cs.counter_id"
                        + " JOIN zone z ON z.id = c.zone_id"
                        + " WHERE br.ended_at IS NOT NULL");
        List<Object> args = new ArrayList<>();
        appendSessionRange(sql, args, filter);
        appendSessionScope(sql, args, filter, allowedSites, allowedGroups);
        Long seconds = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        row.put("break_seconds", seconds == null ? 0L : seconds);
    }

    private static void finishCounterRow(Map<String, Object> row) {
        row.putIfAbsent("served", 0L);
        row.putIfAbsent("serving_seconds", 0L);
        row.putIfAbsent("break_seconds", 0L);
        long open = ((Number) row.get("open_seconds")).longValue();
        long serving = ((Number) row.get("serving_seconds")).longValue();
        long breaks = ((Number) row.get("break_seconds")).longValue();
        row.put("idle_seconds", Math.max(0, open - serving - breaks));
        row.put("utilisation_pct", open > 0 ? round2(serving * 100.0 / open) : null);
    }

    // ---- agent report (§15.2's eight KPIs) --------------------------------------------------------------------

    List<Map<String, Object>> agentTicketStats(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        Map<UUID, Map<String, Object>> byAgent = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder(
                "SELECT tf.agent_id, tf.agent_name,"
                        + " count(*) FILTER (WHERE tf.state = 'completed') AS services_served,"
                        + " count(*) FILTER (WHERE tf.state IN ('cancelled', 'no_show')) AS services_cancelled,"
                        + " AVG(tf.wait_seconds) FILTER (WHERE tf.state = 'completed') AS avg_wait_seconds,"
                        + " AVG(tf.service_seconds) FILTER (WHERE tf.state = 'completed') AS avg_service_seconds,"
                        + " COALESCE(SUM(tf.service_seconds) FILTER (WHERE tf.state = 'completed'), 0) AS total_service_seconds"
                        + " FROM reporting.ticket_fact tf WHERE tf.agent_id IS NOT NULL");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" GROUP BY tf.agent_id, tf.agent_name");
        jdbc.query(sql.toString(), (RowCallbackHandler) rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            UUID agentId = rs.getObject("agent_id", UUID.class);
            row.put("agent_id", agentId);
            row.put("agent_name", rs.getString("agent_name"));
            row.put("services_served", rs.getLong("services_served"));
            row.put("services_cancelled", rs.getLong("services_cancelled"));
            row.put("avg_wait_seconds", nullableDouble(rs, "avg_wait_seconds"));
            row.put("avg_service_seconds", nullableDouble(rs, "avg_service_seconds"));
            row.put("total_service_seconds", rs.getLong("total_service_seconds"));
            byAgent.put(agentId, row);
        }, args.toArray());
        return List.copyOf(byAgent.values());
    }

    /** The same five ticket-derived agent metrics, ungrouped, for one comparison total (never summed or averaged
     * from {@link #agentTicketStats}'s own grouped rows, FR-MON-010's own reasoning applied to every average here). */
    Map<String, Object> agentTicketStatsTotal(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FILTER (WHERE tf.state = 'completed') AS services_served,"
                        + " count(*) FILTER (WHERE tf.state IN ('cancelled', 'no_show')) AS services_cancelled,"
                        + " AVG(tf.wait_seconds) FILTER (WHERE tf.state = 'completed') AS avg_wait_seconds,"
                        + " AVG(tf.service_seconds) FILTER (WHERE tf.state = 'completed') AS avg_service_seconds,"
                        + " COALESCE(SUM(tf.service_seconds) FILTER (WHERE tf.state = 'completed'), 0) AS total_service_seconds"
                        + " FROM reporting.ticket_fact tf WHERE tf.agent_id IS NOT NULL");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, filter, allowedSites, allowedGroups);
        Map<String, Object> row = new LinkedHashMap<>();
        jdbc.query(sql.toString(), (RowCallbackHandler) rs -> {
            row.put("services_served", rs.getLong("services_served"));
            row.put("services_cancelled", rs.getLong("services_cancelled"));
            row.put("avg_wait_seconds", nullableDouble(rs, "avg_wait_seconds"));
            row.put("avg_service_seconds", nullableDouble(rs, "avg_service_seconds"));
            row.put("total_service_seconds", rs.getLong("total_service_seconds"));
        }, args.toArray());
        return row;
    }

    /** {@code agent_id, site_id -> open_seconds}: the raw material {@code OperationalReportService} turns into
     * login adherence, picking each Agent's own busiest Site as its "primary" one (§28.3's own out-of-scope note on
     * rostering means there is no per-Agent home Site to read instead). */
    List<Object[]> agentSessionSeconds(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups, Instant now) {
        StringBuilder sql = new StringBuilder(
                "SELECT cs.agent_id, z.site_id,"
                        + " COALESCE(SUM(EXTRACT(EPOCH FROM (COALESCE(cs.closed_at, ?) - cs.opened_at))), 0) AS open_seconds"
                        + " FROM counter_session cs JOIN counter c ON c.id = cs.counter_id JOIN zone z ON z.id = c.zone_id"
                        + " WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(now));
        appendSessionRange(sql, args, filter);
        appendSessionScope(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" GROUP BY cs.agent_id, z.site_id");
        return jdbc.query(sql.toString(), (rs, i) -> new Object[] {
                rs.getObject("agent_id", UUID.class), rs.getObject("site_id", UUID.class), rs.getLong("open_seconds")
        }, args.toArray());
    }

    /** ISO weekday (1 Monday .. 7 Sunday) to that day's open seconds for one Site's {@code business_hours}; empty
     * means the Site has none configured (unrestricted, see {@link RosteredHours}). */
    Map<Integer, Long> businessHours(UUID siteId) {
        Map<Integer, Long> hours = new LinkedHashMap<>();
        jdbc.query(
                "SELECT weekday, EXTRACT(EPOCH FROM (close_time - open_time)) AS seconds FROM business_hours WHERE scope_type = 'site' AND scope_id = ?",
                (RowCallbackHandler) rs -> hours.put(rs.getInt("weekday"), rs.getLong("seconds")),
                siteId);
        return hours;
    }

    // ---- service / department / site reports (§16.1: volume, avg/P90 wait, avg handling time, SLA attainment) ---

    List<Map<String, Object>> serviceRows(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        return grainRows(filter, allowedSites, allowedGroups, "tf.service_id", "tf.service_name", "service_id", "service_name", true);
    }

    Map<String, Object> serviceTotals(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        return grainRows(filter, allowedSites, allowedGroups, "true", "true", "service_id", "service_name", false).stream()
                .findFirst()
                .map(OperationalReportReads::dropGrainKeys)
                .orElseGet(LinkedHashMap::new);
    }

    List<Map<String, Object>> departmentRows(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        return grainRows(filter, allowedSites, allowedGroups, "tf.service_group_id", "tf.service_group_name", "service_group_id", "service_group_name", true);
    }

    Map<String, Object> departmentTotals(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        return grainRows(filter, allowedSites, allowedGroups, "true", "true", "service_group_id", "service_group_name", false).stream()
                .findFirst()
                .map(OperationalReportReads::dropGrainKeys)
                .orElseGet(LinkedHashMap::new);
    }

    List<Map<String, Object>> siteRows(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        return grainRows(filter, allowedSites, allowedGroups, "tf.site_id", "tf.site_name", "site_id", "site_name", true);
    }

    Map<String, Object> siteTotals(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        return grainRows(filter, allowedSites, allowedGroups, "true", "true", "site_id", "site_name", false).stream()
                .findFirst()
                .map(OperationalReportReads::dropGrainKeys)
                .orElseGet(LinkedHashMap::new);
    }

    /** Volume, average and P90 wait, average handling time and SLA attainment (§16.1), grouped by {@code idExpr}/
     * {@code nameExpr} — the same shape for the service, department and site reports, and (ungrouped, {@code
     * idExpr = "true"}) for each one's comparison total. SLA attainment is joined against each row's own Service's
     * {@code sla_wait_minutes}: a department or Site row rolls up every Service under it (§16.1's own "rolled-up
     * service report"), each still measured against its own SLA rather than one borrowed threshold. */
    private List<Map<String, Object>> grainRows(
            DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups,
            String idExpr, String nameExpr, String idAlias, String nameAlias, boolean group) {
        StringBuilder sql = new StringBuilder("SELECT "
                + idExpr + " AS " + idAlias + ", " + nameExpr + " AS " + nameAlias + ","
                + " count(*) AS volume,"
                + " AVG(tf.wait_seconds) AS avg_wait_seconds,"
                + " percentile_cont(0.9) WITHIN GROUP (ORDER BY tf.wait_seconds) AS p90_wait_seconds,"
                + " AVG(tf.service_seconds) FILTER (WHERE tf.state = 'completed') AS avg_handling_seconds,"
                + " count(*) FILTER (WHERE tf.state = 'completed') AS completed,"
                + " count(*) FILTER (WHERE tf.state = 'completed' AND tf.wait_seconds <= sv.sla_wait_minutes * 60) AS within_sla"
                + " FROM reporting.ticket_fact tf JOIN service sv ON sv.id = tf.service_id WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, filter, allowedSites, allowedGroups);
        if (group) sql.append(" GROUP BY 1, 2 ORDER BY 2");
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(idAlias, group ? rs.getObject(idAlias, UUID.class) : true);
            row.put(nameAlias, group ? rs.getString(nameAlias) : true);
            row.put("volume", rs.getLong("volume"));
            row.put("avg_wait_seconds", nullableDouble(rs, "avg_wait_seconds"));
            row.put("p90_wait_seconds", nullableDouble(rs, "p90_wait_seconds"));
            row.put("avg_handling_seconds", nullableDouble(rs, "avg_handling_seconds"));
            long completed = rs.getLong("completed");
            long withinSla = rs.getLong("within_sla");
            row.put("sla_attainment_pct", completed > 0 ? round2(withinSla * 100.0 / completed) : null);
            return row;
        }, args.toArray());
    }

    private static Map<String, Object> dropGrainKeys(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.keySet().removeIf(k -> k.endsWith("_id") || k.endsWith("_name"));
        return copy;
    }

    /** Average and P90 wait per Service, per hour-of-day band (§15.3's own finer grain), returned only alongside
     * the service report. */
    List<Map<String, Object>> waitByHourBand(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder(
                "SELECT tf.service_id, tf.service_name, EXTRACT(HOUR FROM tf.issued_at)::int AS hour_band,"
                        + " AVG(tf.wait_seconds) AS avg_wait_seconds,"
                        + " percentile_cont(0.9) WITHIN GROUP (ORDER BY tf.wait_seconds) AS p90_wait_seconds"
                        + " FROM reporting.ticket_fact tf WHERE tf.wait_seconds IS NOT NULL");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" GROUP BY tf.service_id, tf.service_name, hour_band ORDER BY tf.service_name, hour_band");
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service_id", rs.getObject("service_id", UUID.class));
            row.put("hour_band", rs.getInt("hour_band"));
            row.put("avg_wait_seconds", nullableDouble(rs, "avg_wait_seconds"));
            row.put("p90_wait_seconds", nullableDouble(rs, "p90_wait_seconds"));
            return row;
        }, args.toArray());
    }

    // ---- shared filter plumbing --------------------------------------------------------------------------------

    private void appendFilters(StringBuilder sql, List<Object> args, DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        if (filter.from() != null) {
            sql.append(" AND tf.issued_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND tf.issued_at < ?");
            args.add(Timestamp.from(filter.to()));
        }
        if (filter.siteId() != null) {
            sql.append(" AND tf.site_id = ?");
            args.add(filter.siteId());
        } else if (allowedSites != null) {
            appendIn(sql, args, "tf.site_id", allowedSites);
        }
        if (filter.zoneId() != null) {
            sql.append(" AND tf.zone_id = ?");
            args.add(filter.zoneId());
        }
        if (filter.serviceGroupId() != null) {
            sql.append(" AND tf.service_group_id = ?");
            args.add(filter.serviceGroupId());
        } else if (allowedGroups != null) {
            appendIn(sql, args, "tf.service_group_id", allowedGroups);
        }
        if (filter.serviceId() != null) {
            sql.append(" AND tf.service_id = ?");
            args.add(filter.serviceId());
        }
        if (filter.agentId() != null) {
            sql.append(" AND tf.agent_id = ?");
            args.add(filter.agentId());
        }
        if (filter.priorityClassId() != null) {
            sql.append(" AND tf.priority_class_id = ?");
            args.add(filter.priorityClassId());
        }
        if (filter.channel() != null) {
            sql.append(" AND tf.channel = ?");
            args.add(filter.channel());
        }
        if (filter.visitorCategory() != null) {
            sql.append(" AND tf.visitor_category = ?");
            args.add(filter.visitorCategory());
        }
    }

    /** The live {@code counter_session}/{@code break_record} queries share the same date range as every other
     * report (a session or a break belongs to the period it started in, the same "attributed by its own start"
     * rule {@link #peakConcurrency} documents), but none of {@code ticket_fact}'s ticket-grained filters (Service,
     * priority class, channel, visitor category) apply to a session or a break, which belong to no one ticket. */
    private void appendSessionRange(StringBuilder sql, List<Object> args, DetailedTokenReportFilter filter) {
        if (filter.from() != null) {
            sql.append(" AND cs.opened_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND cs.opened_at < ?");
            args.add(Timestamp.from(filter.to()));
        }
    }

    private void appendSessionScope(StringBuilder sql, List<Object> args, DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        if (filter.siteId() != null) {
            sql.append(" AND z.site_id = ?");
            args.add(filter.siteId());
        } else if (allowedSites != null) {
            appendIn(sql, args, "z.site_id", allowedSites);
        }
        if (filter.zoneId() != null) {
            sql.append(" AND z.id = ?");
            args.add(filter.zoneId());
        }
        if (filter.agentId() != null) {
            sql.append(" AND cs.agent_id = ?");
            args.add(filter.agentId());
        }
        // allowedGroups has no direct counter-session column to narrow by (a session is not tied to one Service
        // group); a caller scoped to specific groups but not sites still reaches every counter at their own Sites.
    }

    private void appendIn(StringBuilder sql, List<Object> args, String column, Set<UUID> ids) {
        if (ids.isEmpty()) {
            sql.append(" AND 1 = 0");
            return;
        }
        sql.append(" AND ").append(column).append(" IN (");
        boolean first = true;
        for (UUID id : ids) {
            sql.append(first ? "?" : ", ?");
            args.add(id);
            first = false;
        }
        sql.append(")");
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static Double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
