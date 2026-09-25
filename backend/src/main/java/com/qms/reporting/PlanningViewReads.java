package com.qms.reporting;

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
 * The read path of ticket 51's two planning views (§16.2). Peak-hours reads {@code reporting.ticket_fact} alone,
 * the same table every ticket-grained report already reads exclusively. Staffing-gap additionally reads the live
 * {@code counter_session} table directly for counter-hours (the same "read another context's staff-occupancy table
 * directly" shape {@code OperationalReportReads} already sets for the counter/agent reports) and re-uses the exact
 * SLA-attainment join {@code OperationalReportReads#grainRows} already established (a Ticket's completed wait
 * against its own Service's {@code sla_wait_minutes}), bucketed by hour instead of by Service/Department/Site.
 */
@Repository
class PlanningViewReads {

    private final JdbcTemplate jdbc;

    PlanningViewReads(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- peak-hours (FR-RPT-011): volume by hour-of-day x ISO day-of-week ----------------------------------------
    // Every hour and weekday here is the site's own local one (its `timezone`): a planning view reads "11:00 is busy"
    // on the wall clock the staff work to, not in UTC.

    List<Map<String, Object>> peakHours(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder(
                "SELECT EXTRACT(ISODOW FROM tf.issued_at AT TIME ZONE st.timezone)::int AS day_of_week,"
                        + " EXTRACT(HOUR FROM tf.issued_at AT TIME ZONE st.timezone)::int AS hour_of_day,"
                        + " count(*) AS ticket_count"
                        + " FROM reporting.ticket_fact tf JOIN site st ON st.id = tf.site_id WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendTicketFactFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" GROUP BY 1, 2 ORDER BY 1, 2");
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day_of_week", rs.getInt("day_of_week"));
            row.put("hour_of_day", rs.getInt("hour_of_day"));
            row.put("ticket_count", rs.getLong("ticket_count"));
            return row;
        }, args.toArray());
    }

    // ---- staffing-gap (FR-RPT-012): tickets offered vs counter-hours available vs SLA attainment, per hour band ----

    List<Map<String, Object>> staffingGap(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups, Instant now) {
        Map<Integer, Long> ticketsOffered = ticketsOfferedByHour(filter, allowedSites, allowedGroups);
        Map<Integer, Double> counterHours = counterHoursByHour(filter, allowedSites, now);
        Map<Integer, long[]> sla = slaByHour(filter, allowedSites, allowedGroups);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("hour_of_day", hour);
            row.put("tickets_offered", ticketsOffered.getOrDefault(hour, 0L));
            row.put("counter_hours_available", round2(counterHours.getOrDefault(hour, 0.0)));
            long[] slaCounts = sla.get(hour);
            row.put("sla_attainment_pct", slaCounts == null || slaCounts[0] == 0 ? null : round2(slaCounts[1] * 100.0 / slaCounts[0]));
            rows.add(row);
        }
        return rows;
    }

    private Map<Integer, Long> ticketsOfferedByHour(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder(
                "SELECT EXTRACT(HOUR FROM tf.issued_at AT TIME ZONE st.timezone)::int AS hour_of_day, count(*) FILTER (WHERE tf.is_chain_head) AS tickets_offered"
                        + " FROM reporting.ticket_fact tf JOIN site st ON st.id = tf.site_id WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendTicketFactFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" GROUP BY 1");
        Map<Integer, Long> byHour = new LinkedHashMap<>();
        jdbc.query(sql.toString(), (RowCallbackHandler) rs -> byHour.put(rs.getInt("hour_of_day"), rs.getLong("tickets_offered")), args.toArray());
        return byHour;
    }

    /** Counter-hours available, attributed to the hour a session started (the same "attributed by its own start"
     * rule {@code OperationalReportReads#peakConcurrency}/{@code #appendSessionRange} already document for a
     * session or a break belonging to the period it began in). */
    private Map<Integer, Double> counterHoursByHour(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Instant now) {
        StringBuilder sql = new StringBuilder(
                "SELECT EXTRACT(HOUR FROM cs.opened_at AT TIME ZONE st.timezone)::int AS hour_of_day,"
                        + " SUM(EXTRACT(EPOCH FROM (COALESCE(cs.closed_at, ?) - cs.opened_at))) / 3600.0 AS open_hours"
                        + " FROM counter_session cs JOIN counter c ON c.id = cs.counter_id JOIN zone z ON z.id = c.zone_id JOIN site st ON st.id = z.site_id"
                        + " WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(now));
        if (filter.from() != null) {
            sql.append(" AND cs.opened_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND cs.opened_at < ?");
            args.add(Timestamp.from(filter.to()));
        }
        if (filter.siteId() != null) {
            sql.append(" AND z.site_id = ?");
            args.add(filter.siteId());
        } else if (allowedSites != null) {
            appendIn(sql, args, "z.site_id", allowedSites);
        }
        sql.append(" GROUP BY 1");
        Map<Integer, Double> byHour = new LinkedHashMap<>();
        jdbc.query(sql.toString(), (RowCallbackHandler) rs -> byHour.put(rs.getInt("hour_of_day"), rs.getDouble("open_hours")), args.toArray());
        return byHour;
    }

    /** {@code hour -> [completed, within_sla]}, the same join {@code OperationalReportReads#grainRows} already
     * uses for SLA attainment, bucketed by hour instead of by grain id. */
    private Map<Integer, long[]> slaByHour(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder(
                "SELECT EXTRACT(HOUR FROM tf.issued_at AT TIME ZONE st.timezone)::int AS hour_of_day,"
                        + " count(*) FILTER (WHERE tf.state = 'completed') AS completed,"
                        + " count(*) FILTER (WHERE tf.state = 'completed' AND tf.wait_seconds <= sv.sla_wait_minutes * 60) AS within_sla"
                        + " FROM reporting.ticket_fact tf JOIN service sv ON sv.id = tf.service_id JOIN site st ON st.id = tf.site_id WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendTicketFactFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" GROUP BY 1");
        Map<Integer, long[]> byHour = new LinkedHashMap<>();
        jdbc.query(sql.toString(), (RowCallbackHandler) rs -> byHour.put(rs.getInt("hour_of_day"), new long[] {rs.getLong("completed"), rs.getLong("within_sla")}), args.toArray());
        return byHour;
    }

    // ---- shared filter plumbing (the same shape OperationalReportReads#appendFilters already has) -----------------

    private void appendTicketFactFilters(StringBuilder sql, List<Object> args, DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
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

    private static Double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
