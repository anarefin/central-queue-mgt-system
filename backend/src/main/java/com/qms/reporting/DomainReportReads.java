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
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The read path of ticket 51's four row-shaped report keys (SRS §16.1): Appointment, Journey, Feedback and
 * Notification (the fifth, Audit, reuses {@code com.qms.audit.AuditQueryService} directly and never comes through
 * here). Every query reads its own context's live table directly (never a reporting-store fact table built for it,
 * unlike ticket 48/50's ticket-grained reports) — the same "read another context's table for a reporting-style
 * view" shape {@code OperationalReportReads} already sets for the counter/agent reports' session and break time,
 * appropriate here too since none of these four tables sees anywhere near the volume {@code reporting.ticket_fact}
 * exists to protect against. The Journey report's own per-stop wait still reads {@code reporting.ticket_fact} (the
 * one place wait/service seconds are denormalised) joined out from {@code journey_stop.ticket_id}.
 */
@Repository
class DomainReportReads {

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    DomainReportReads(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ================================================================================================================
    // Appointment report (§16.1: "Booked, source, checked in, punctuality, no-show, lead time"; FR-APT-043)
    // ================================================================================================================

    private static final String APPOINTMENT_FROM =
            " FROM appointment a"
                    + " JOIN service sv ON sv.id = a.service_id"
                    + " JOIN service_group sg ON sg.id = sv.service_group_id"
                    + " JOIN site st ON st.id = sg.site_id"
                    + " LEFT JOIN users u ON u.id = a.preferred_agent_id"
                    + " LEFT JOIN visitor v ON v.id = a.visitor_id"
                    + " WHERE 1 = 1";

    long appointmentTotalRows(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder("SELECT count(*)" + APPOINTMENT_FROM);
        List<Object> args = new ArrayList<>();
        appendAppointmentFilters(sql, args, filter, allowedSites, allowedGroups);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    List<Map<String, Object>> appointmentPage(
            DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.id AS appointment_id, a.reference_code, a.service_id, sv.name_i18n AS service_name,"
                        + " a.preferred_agent_id AS agent_id, u.display_name AS agent_name, v.category AS visitor_category,"
                        + " a.source, a.state, a.created_at AS booked_at, a.slot_date, a.slot_start,"
                        + " ((a.slot_date + a.slot_start) AT TIME ZONE st.timezone) AS slot_at,"
                        + " a.checked_in_at, a.checkin_variance_seconds,"
                        + " EXTRACT(EPOCH FROM (((a.slot_date + a.slot_start) AT TIME ZONE st.timezone) - a.created_at)) AS lead_time_seconds"
                        + APPOINTMENT_FROM);
        List<Object> args = new ArrayList<>();
        appendAppointmentFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" ORDER BY a.created_at DESC, a.id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("appointment_id", rs.getObject("appointment_id", UUID.class));
            row.put("reference_code", rs.getString("reference_code"));
            row.put("service_id", rs.getObject("service_id", UUID.class));
            row.put("service_name", names(rs.getString("service_name")));
            row.put("agent_id", rs.getObject("agent_id", UUID.class));
            row.put("agent_name", rs.getString("agent_name"));
            row.put("visitor_category", rs.getString("visitor_category"));
            row.put("source", rs.getString("source"));
            row.put("state", rs.getString("state"));
            row.put("booked_at", instant(rs.getTimestamp("booked_at")));
            row.put("slot_date", rs.getObject("slot_date", java.time.LocalDate.class));
            row.put("slot_start", rs.getObject("slot_start", java.time.LocalTime.class));
            row.put("slot_at", instant(rs.getTimestamp("slot_at")));
            row.put("checked_in_at", instant(rs.getTimestamp("checked_in_at")));
            row.put("checkin_variance_seconds", (Integer) rs.getObject("checkin_variance_seconds"));
            row.put("lead_time_seconds", nullableLong(rs, "lead_time_seconds"));
            row.put("no_show", "no_show".equals(rs.getString("state")));
            return row;
        }, args.toArray());
    }

    /** FR-APT-043: no-show rate per Service — the same filter and joins the page itself uses. */
    List<Map<String, Object>> appointmentNoShowRateByService(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.service_id AS grp_id, sv.name_i18n AS grp_name,"
                        + " count(*) AS total, count(*) FILTER (WHERE a.state = 'no_show') AS no_show"
                        + APPOINTMENT_FROM);
        List<Object> args = new ArrayList<>();
        appendAppointmentFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" GROUP BY 1, 2 ORDER BY 2");
        return jdbc.query(sql.toString(), (rs, i) -> noShowRow(rs.getObject("grp_id", UUID.class), names(rs.getString("grp_name")), rs), args.toArray());
    }

    /** FR-APT-043: no-show rate per preferred Agent (null groups the appointments left unassigned). */
    List<Map<String, Object>> appointmentNoShowRateByAgent(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.preferred_agent_id AS grp_id, u.display_name AS grp_name,"
                        + " count(*) AS total, count(*) FILTER (WHERE a.state = 'no_show') AS no_show"
                        + APPOINTMENT_FROM);
        List<Object> args = new ArrayList<>();
        appendAppointmentFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" GROUP BY 1, 2 ORDER BY 2");
        return jdbc.query(sql.toString(), (rs, i) -> noShowRow(rs.getObject("grp_id", UUID.class), rs.getString("grp_name"), rs), args.toArray());
    }

    /** FR-APT-043: no-show rate per visitor category (null groups appointments with no known visitor category). */
    List<Map<String, Object>> appointmentNoShowRateByVisitorCategory(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder(
                "SELECT v.category AS grp_id, v.category AS grp_name,"
                        + " count(*) AS total, count(*) FILTER (WHERE a.state = 'no_show') AS no_show"
                        + APPOINTMENT_FROM);
        List<Object> args = new ArrayList<>();
        appendAppointmentFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" GROUP BY 1, 2 ORDER BY 2");
        return jdbc.query(sql.toString(), (rs, i) -> noShowRow(rs.getString("grp_id"), rs.getString("grp_name"), rs), args.toArray());
    }

    private Map<String, Object> noShowRow(Object id, Object name, ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        long total = rs.getLong("total");
        long noShow = rs.getLong("no_show");
        row.put("total", total);
        row.put("no_show", noShow);
        row.put("no_show_rate_pct", total > 0 ? round2(noShow * 100.0 / total) : null);
        return row;
    }

    /** The §15.3 "appointment adherence" KPI ticket 50 left for this ticket: among appointments whose slot has
     * already been resolved one way or the other (checked in, converted from check-in, or swept to no-show —
     * {@code held_slot}/{@code booked}/{@code rescheduled}/{@code cancelled} are excluded, since those never
     * reached that moment), the share that were not a no-show. */
    Map<String, Object> appointmentAdherence(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FILTER (WHERE a.state IN ('checked_in', 'converted', 'no_show')) AS resolved,"
                        + " count(*) FILTER (WHERE a.state IN ('checked_in', 'converted')) AS shown"
                        + APPOINTMENT_FROM);
        List<Object> args = new ArrayList<>();
        appendAppointmentFilters(sql, args, filter, allowedSites, allowedGroups);
        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.query(sql.toString(), rs -> {
            long resolved = rs.getLong("resolved");
            long shown = rs.getLong("shown");
            out.put("resolved", resolved);
            out.put("shown", shown);
            out.put("adherence_pct", resolved > 0 ? round2(shown * 100.0 / resolved) : null);
        }, args.toArray());
        return out;
    }

    private void appendAppointmentFilters(
            StringBuilder sql, List<Object> args, DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        if (filter.from() != null) {
            sql.append(" AND ((a.slot_date + a.slot_start) AT TIME ZONE st.timezone) >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND ((a.slot_date + a.slot_start) AT TIME ZONE st.timezone) < ?");
            args.add(Timestamp.from(filter.to()));
        }
        if (filter.siteId() != null) {
            sql.append(" AND st.id = ?");
            args.add(filter.siteId());
        } else if (allowedSites != null) {
            appendIn(sql, args, "st.id", allowedSites);
        }
        if (filter.serviceGroupId() != null) {
            sql.append(" AND sg.id = ?");
            args.add(filter.serviceGroupId());
        } else if (allowedGroups != null) {
            appendIn(sql, args, "sg.id", allowedGroups);
        }
        if (filter.serviceId() != null) {
            sql.append(" AND a.service_id = ?");
            args.add(filter.serviceId());
        }
        if (filter.agentId() != null) {
            sql.append(" AND a.preferred_agent_id = ?");
            args.add(filter.agentId());
        }
        if (filter.visitorCategory() != null) {
            sql.append(" AND v.category = ?");
            args.add(filter.visitorCategory());
        }
    }

    // ================================================================================================================
    // Journey report (§16.1: "Stops planned, stops completed, total time on site"; FR-QUE-064)
    // ================================================================================================================

    private static final String JOURNEY_FROM =
            " FROM visit vi"
                    + " JOIN site st ON st.id = vi.site_id"
                    + " JOIN journey_stop js ON js.visit_id = vi.id"
                    + " LEFT JOIN reporting.ticket_fact tf ON tf.ticket_id = js.ticket_id"
                    + " WHERE 1 = 1";

    long journeyTotalRows(DetailedTokenReportFilter filter, Set<UUID> allowedSites) {
        StringBuilder sql = new StringBuilder("SELECT count(DISTINCT vi.id)" + JOURNEY_FROM);
        List<Object> args = new ArrayList<>();
        appendJourneyFilters(sql, args, filter, allowedSites);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    List<Map<String, Object>> journeyPage(DetailedTokenReportFilter filter, Set<UUID> allowedSites, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT vi.id AS visit_id, vi.site_id, st.name AS site_name, vi.started_at, vi.ended_at,"
                        + " count(js.id) AS stops_planned,"
                        + " count(js.id) FILTER (WHERE tf.state = 'completed') AS stops_completed"
                        + JOURNEY_FROM);
        List<Object> args = new ArrayList<>();
        appendJourneyFilters(sql, args, filter, allowedSites);
        sql.append(" GROUP BY vi.id, vi.site_id, st.name, vi.started_at, vi.ended_at ORDER BY vi.started_at DESC, vi.id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("visit_id", rs.getObject("visit_id", UUID.class));
            row.put("site_id", rs.getObject("site_id", UUID.class));
            row.put("site_name", rs.getString("site_name"));
            Instant startedAt = instant(rs.getTimestamp("started_at"));
            Instant endedAt = instant(rs.getTimestamp("ended_at"));
            row.put("started_at", startedAt);
            row.put("ended_at", endedAt);
            row.put("stops_planned", rs.getLong("stops_planned"));
            row.put("stops_completed", rs.getLong("stops_completed"));
            row.put("total_time_on_site_seconds", endedAt == null ? null : endedAt.getEpochSecond() - startedAt.getEpochSecond());
            return row;
        }, args.toArray());
    }

    /** FR-QUE-064's own aggregate: journey completion rate (the §15.3 KPI ticket 50 left for this ticket) and the
     * average/P90 per-stop wait, from {@code reporting.ticket_fact}'s own {@code wait_seconds}, never averaged from
     * an already-grouped figure (the same FR-MON-010 discipline {@code OperationalReportReads} follows). */
    Map<String, Object> journeyAggregate(DetailedTokenReportFilter filter, Set<UUID> allowedSites) {
        StringBuilder completionSql = new StringBuilder(
                "WITH v AS (SELECT vi.id, count(js.id) AS planned, count(js.id) FILTER (WHERE tf.state = 'completed') AS completed"
                        + JOURNEY_FROM);
        List<Object> completionArgs = new ArrayList<>();
        appendJourneyFilters(completionSql, completionArgs, filter, allowedSites);
        completionSql.append(" GROUP BY vi.id)"
                + " SELECT count(*) AS total_journeys, count(*) FILTER (WHERE completed = planned AND planned > 0) AS completed_journeys FROM v");

        StringBuilder waitSql = new StringBuilder(
                "SELECT AVG(tf.wait_seconds) AS avg_wait_seconds, percentile_cont(0.9) WITHIN GROUP (ORDER BY tf.wait_seconds) AS p90_wait_seconds"
                        + JOURNEY_FROM + " AND tf.wait_seconds IS NOT NULL");
        List<Object> waitArgs = new ArrayList<>();
        appendJourneyFilters(waitSql, waitArgs, filter, allowedSites);

        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.query(completionSql.toString(), rs -> {
            long total = rs.getLong("total_journeys");
            long completed = rs.getLong("completed_journeys");
            out.put("total_journeys", total);
            out.put("completed_journeys", completed);
            out.put("completion_rate_pct", total > 0 ? round2(completed * 100.0 / total) : null);
        }, completionArgs.toArray());
        jdbc.query(waitSql.toString(), rs -> {
            out.put("avg_stop_wait_seconds", nullableDouble(rs, "avg_wait_seconds"));
            out.put("p90_stop_wait_seconds", nullableDouble(rs, "p90_wait_seconds"));
        }, waitArgs.toArray());
        return out;
    }

    /** Only Site scoping (never Service group): one Visit's own stops can span several Services and their groups at
     * once (ADR-0007), so there is no single group a whole Visit row belongs to the way a Ticket does. */
    private void appendJourneyFilters(StringBuilder sql, List<Object> args, DetailedTokenReportFilter filter, Set<UUID> allowedSites) {
        if (filter.from() != null) {
            sql.append(" AND vi.started_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND vi.started_at < ?");
            args.add(Timestamp.from(filter.to()));
        }
        if (filter.siteId() != null) {
            sql.append(" AND vi.site_id = ?");
            args.add(filter.siteId());
        } else if (allowedSites != null) {
            appendIn(sql, args, "vi.site_id", allowedSites);
        }
    }

    // ================================================================================================================
    // Feedback report (§16.1: "Rating, comment, agent, service")
    // ================================================================================================================

    private static final String FEEDBACK_FROM =
            " FROM feedback f"
                    + " JOIN ticket t ON t.id = f.ticket_id"
                    + " JOIN service sv ON sv.id = t.service_id"
                    + " LEFT JOIN users u ON u.id = t.agent_id"
                    + " WHERE 1 = 1";

    long feedbackTotalRows(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder("SELECT count(*)" + FEEDBACK_FROM);
        List<Object> args = new ArrayList<>();
        appendFeedbackFilters(sql, args, filter, allowedSites, allowedGroups);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    List<Map<String, Object>> feedbackPage(
            DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT f.id AS feedback_id, f.ticket_id, f.rating, f.comment, (f.comment_approved_at IS NOT NULL) AS comment_approved,"
                        + " t.agent_id, u.display_name AS agent_name, t.service_id, sv.name_i18n AS service_name, f.submitted_at"
                        + FEEDBACK_FROM);
        List<Object> args = new ArrayList<>();
        appendFeedbackFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" ORDER BY f.submitted_at DESC, f.id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("feedback_id", rs.getObject("feedback_id", UUID.class));
            row.put("ticket_id", rs.getObject("ticket_id", UUID.class));
            row.put("rating", rs.getInt("rating"));
            row.put("comment", rs.getString("comment"));
            row.put("comment_approved", rs.getBoolean("comment_approved"));
            row.put("agent_id", rs.getObject("agent_id", UUID.class));
            row.put("agent_name", rs.getString("agent_name"));
            row.put("service_id", rs.getObject("service_id", UUID.class));
            row.put("service_name", names(rs.getString("service_name")));
            row.put("submitted_at", instant(rs.getTimestamp("submitted_at")));
            return row;
        }, args.toArray());
    }

    Map<String, Object> feedbackTotals(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder("SELECT count(*) AS count, AVG(f.rating) AS avg_rating" + FEEDBACK_FROM);
        List<Object> args = new ArrayList<>();
        appendFeedbackFilters(sql, args, filter, allowedSites, allowedGroups);
        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.query(sql.toString(), rs -> {
            out.put("count", rs.getLong("count"));
            out.put("avg_rating", nullableDouble(rs, "avg_rating"));
        }, args.toArray());
        return out;
    }

    private void appendFeedbackFilters(
            StringBuilder sql, List<Object> args, DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        if (filter.from() != null) {
            sql.append(" AND f.submitted_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND f.submitted_at < ?");
            args.add(Timestamp.from(filter.to()));
        }
        if (filter.siteId() != null) {
            sql.append(" AND t.site_id = ?");
            args.add(filter.siteId());
        } else if (allowedSites != null) {
            appendIn(sql, args, "t.site_id", allowedSites);
        }
        if (filter.serviceGroupId() != null) {
            sql.append(" AND t.service_group_id = ?");
            args.add(filter.serviceGroupId());
        } else if (allowedGroups != null) {
            appendIn(sql, args, "t.service_group_id", allowedGroups);
        }
        if (filter.serviceId() != null) {
            sql.append(" AND t.service_id = ?");
            args.add(filter.serviceId());
        }
        if (filter.agentId() != null) {
            sql.append(" AND t.agent_id = ?");
            args.add(filter.agentId());
        }
    }

    // ================================================================================================================
    // Notification report (§16.1: "Trigger, channel, status, cost indicator")
    // ================================================================================================================

    private static final String NOTIFICATION_FROM =
            " FROM notification_message nm"
                    + " LEFT JOIN site st ON st.id = nm.site_id"
                    + " LEFT JOIN service sv ON sv.id = nm.service_id"
                    + " WHERE 1 = 1";

    long notificationTotalRows(DetailedTokenReportFilter filter, Set<UUID> allowedSites) {
        StringBuilder sql = new StringBuilder("SELECT count(*)" + NOTIFICATION_FROM);
        List<Object> args = new ArrayList<>();
        appendNotificationFilters(sql, args, filter, allowedSites);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    List<Map<String, Object>> notificationPage(DetailedTokenReportFilter filter, Set<UUID> allowedSites, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT nm.id, nm.trigger_key, nm.channel, nm.status, nm.site_id, st.name AS site_name,"
                        + " nm.service_id, sv.name_i18n AS service_name, nm.created_at, nm.sent_at"
                        + NOTIFICATION_FROM);
        List<Object> args = new ArrayList<>();
        appendNotificationFilters(sql, args, filter, allowedSites);
        sql.append(" ORDER BY nm.created_at DESC, nm.id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getObject("id", UUID.class));
            row.put("trigger_key", rs.getString("trigger_key"));
            String channel = rs.getString("channel");
            row.put("channel", channel);
            row.put("status", rs.getString("status"));
            row.put("cost_indicator", NotificationCostIndicator.forChannel(channel));
            row.put("site_id", rs.getObject("site_id", UUID.class));
            row.put("site_name", rs.getString("site_name"));
            row.put("service_id", rs.getObject("service_id", UUID.class));
            row.put("service_name", names(rs.getString("service_name")));
            row.put("created_at", instant(rs.getTimestamp("created_at")));
            row.put("sent_at", instant(rs.getTimestamp("sent_at")));
            return row;
        }, args.toArray());
    }

    private void appendNotificationFilters(StringBuilder sql, List<Object> args, DetailedTokenReportFilter filter, Set<UUID> allowedSites) {
        if (filter.from() != null) {
            sql.append(" AND nm.created_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND nm.created_at < ?");
            args.add(Timestamp.from(filter.to()));
        }
        if (filter.siteId() != null) {
            sql.append(" AND nm.site_id = ?");
            args.add(filter.siteId());
        } else if (allowedSites != null) {
            appendIn(sql, args, "nm.site_id", allowedSites);
        }
        if (filter.serviceId() != null) {
            sql.append(" AND nm.service_id = ?");
            args.add(filter.serviceId());
        }
    }

    // ---- shared plumbing (the same shape DetailedTokenReportReads/OperationalReportReads each already carry) -----

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

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : Math.round(((Number) value).doubleValue());
    }

    private static Double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> names(String json) {
        return json == null ? Map.of() : new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }
}
