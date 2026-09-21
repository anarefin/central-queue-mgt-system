package com.qms.dashboard;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dashboard's own SQL (SRS §15.1, ticket 46): every tile of FR-MON-003 plus FR-QUE-033's served-per-counter view,
 * read straight off the tables that queue, session, device, appointment and configuration.site already own — the
 * same "read another context's table directly rather than depend on its package-private repository" shape
 * {@code com.qms.queue.RemoteArrivalService} sets out for {@code service_remote_rule} (ticket 43). Nothing here
 * writes anything.
 */
@Repository
class DashboardReads {

    /** {@code com.qms.device.DeviceService}'s own "offline" boundary (not shared as a public constant): no heartbeat
     * within this long. Kept in step with it by hand since a device's own health view is that context's, not this
     * one's, to expose. */
    private static final Duration DEVICE_OFFLINE_AFTER = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    DashboardReads(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---- filter validation -----------------------------------------------------------------------------------

    record SiteRow(String name, ZoneId timezone) {}

    SiteRow site(UUID siteId) {
        return jdbc.query(
                        "SELECT name, timezone FROM site WHERE id = ?", (rs, i) -> new SiteRow(rs.getString("name"), ZoneId.of(rs.getString("timezone"))), siteId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** Confirms a Zone belongs to the Site the filter is otherwise scoped to, else {@code not_found}. */
    void requireZoneInSite(UUID zoneId, UUID siteId) {
        Boolean ok = jdbc.query("SELECT site_id = ? FROM zone WHERE id = ?", rs -> rs.next() ? rs.getBoolean(1) : null, siteId, zoneId);
        if (!Boolean.TRUE.equals(ok)) throw new ApiException(ErrorCode.NOT_FOUND);
    }

    /** Confirms a Service group belongs to the Site the filter is otherwise scoped to, else {@code not_found}. */
    void requireGroupInSite(UUID groupId, UUID siteId) {
        Boolean ok = jdbc.query("SELECT site_id = ? FROM service_group WHERE id = ?", rs -> rs.next() ? rs.getBoolean(1) : null, siteId, groupId);
        if (!Boolean.TRUE.equals(ok)) throw new ApiException(ErrorCode.NOT_FOUND);
    }

    // ---- shared filter plumbing -------------------------------------------------------------------------------

    /** Appends the ticket-table predicates every tile shares: Site (mandatory), Zone, Service group, Service,
     * Priority class (each optional) and, when the caller's reach is only their own groups (FR-CFG-105), the groups
     * their token's scope claim allows. {@code alias} is the ticket table's alias in the caller's query. */
    private void ticketFilters(StringBuilder sql, List<Object> args, String alias, DashboardFilter filter, List<UUID> allowedGroups) {
        sql.append(" AND ").append(alias).append(".site_id = ?");
        args.add(filter.siteId());
        if (filter.zoneId() != null) {
            sql.append(" AND ").append(alias).append(".zone_id = ?");
            args.add(filter.zoneId());
        }
        if (filter.serviceGroupId() != null) {
            sql.append(" AND ").append(alias).append(".service_group_id = ?");
            args.add(filter.serviceGroupId());
        } else if (allowedGroups != null) {
            appendGroupRestriction(sql, args, alias, allowedGroups);
        }
        if (filter.serviceId() != null) {
            sql.append(" AND ").append(alias).append(".service_id = ?");
            args.add(filter.serviceId());
        }
        if (filter.priorityClassId() != null) {
            sql.append(" AND coalesce(")
                    .append(alias)
                    .append(".priority_class_id, (SELECT id FROM priority_class WHERE is_default)) = ?");
            args.add(filter.priorityClassId());
        }
    }

    private void appendGroupRestriction(StringBuilder sql, List<Object> args, String alias, List<UUID> allowedGroups) {
        appendGroupRestriction(sql, args, alias, "service_group_id", allowedGroups);
    }

    /** {@code column} is the Service group id column as {@code alias}'s own table names it: {@code service_group_id}
     * on {@code ticket}, but plain {@code id} on {@code service_group} itself (appointments join to the latter). */
    private void appendGroupRestriction(StringBuilder sql, List<Object> args, String alias, String column, List<UUID> allowedGroups) {
        if (allowedGroups.isEmpty()) {
            sql.append(" AND 1 = 0");
            return;
        }
        sql.append(" AND ").append(alias).append(".").append(column).append(" IN (");
        for (int i = 0; i < allowedGroups.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
            args.add(allowedGroups.get(i));
        }
        sql.append(")");
    }

    // ---- waiting now (FR-MON-003) -----------------------------------------------------------------------------

    record WaitingGroup(UUID serviceGroupId, Map<String, String> names, int count, long longestWaitSeconds) {}

    /** {@code now} is always the application's own {@link Clock} (bound as a parameter), never SQL's own {@code now()}
     * — the one wall clock a test can move (SRS §21.1's own "5 s stale" is measured against it, not the database's). */
    List<WaitingGroup> waitingNow(DashboardFilter filter, List<UUID> allowedGroups, Instant now) {
        StringBuilder sql = new StringBuilder(
                "SELECT t.service_group_id, sg.name_i18n, count(*) AS cnt,"
                        + " coalesce(max(extract(epoch FROM (?::timestamptz - t.queued_at))), 0)::bigint AS longest_wait_seconds"
                        + " FROM ticket t JOIN service_group sg ON sg.id = t.service_group_id"
                        + " WHERE t.state IN ('waiting', 'paused', 'remote')");
        List<Object> args = new ArrayList<>();
        args.add(java.sql.Timestamp.from(now));
        ticketFilters(sql, args, "t", filter, allowedGroups);
        sql.append(" GROUP BY t.service_group_id, sg.name_i18n ORDER BY cnt DESC");
        return jdbc.query(
                sql.toString(),
                (rs, i) -> new WaitingGroup(rs.getObject("service_group_id", UUID.class), names(rs.getString("name_i18n")), rs.getInt("cnt"), rs.getLong("longest_wait_seconds")),
                args.toArray());
    }

    // ---- serving now (FR-MON-003) -----------------------------------------------------------------------------

    record ServingTicket(UUID ticketId, String tokenNumber, UUID counterId, String counterLabel, UUID agentId, String agentName, long elapsedSeconds) {}

    List<ServingTicket> servingNow(DashboardFilter filter, List<UUID> allowedGroups, Instant now) {
        StringBuilder sql = new StringBuilder(
                "SELECT t.id, t.token_number, t.counter_id, c.label AS counter_label, t.agent_id, u.display_name AS agent_name,"
                        + " coalesce(extract(epoch FROM (?::timestamptz - t.served_at)), 0)::bigint AS elapsed_seconds"
                        + " FROM ticket t JOIN counter c ON c.id = t.counter_id LEFT JOIN users u ON u.id = t.agent_id"
                        + " WHERE t.state = 'serving'");
        List<Object> args = new ArrayList<>();
        args.add(java.sql.Timestamp.from(now));
        ticketFilters(sql, args, "t", filter, allowedGroups);
        sql.append(" ORDER BY t.served_at ASC");
        return jdbc.query(
                sql.toString(),
                (rs, i) -> new ServingTicket(
                        rs.getObject("id", UUID.class), rs.getString("token_number"), rs.getObject("counter_id", UUID.class), rs.getString("counter_label"),
                        rs.getObject("agent_id", UUID.class), rs.getString("agent_name"), rs.getLong("elapsed_seconds")),
                args.toArray());
    }

    // ---- counters (FR-MON-003, FR-QUE-033) --------------------------------------------------------------------

    record Counters(int open, int onBreak, int closed, int idleWithQueue) {}

    Counters counters(DashboardFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT (SELECT state FROM counter_session cs WHERE cs.counter_id = c.id AND cs.state IN ('open', 'on_break', 'closing') LIMIT 1) AS session_state"
                        + " FROM counter c JOIN zone z ON z.id = c.zone_id WHERE c.active = true AND z.site_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(filter.siteId());
        counterScopeFilters(sql, args, filter);
        List<String> states = jdbc.query(sql.toString(), (rs, i) -> rs.getString("session_state"), args.toArray());
        int open = 0;
        int onBreak = 0;
        int closed = 0;
        for (String state : states) {
            if (state == null) closed++;
            else if ("on_break".equals(state)) onBreak++;
            else open++;
        }
        return new Counters(open, onBreak, closed, idleWithQueue(filter));
    }

    /** A counter with no live session whose Service(s) still have a non-empty queue (FR-QUE-033's load-balancing signal). */
    private int idleWithQueue(DashboardFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(DISTINCT c.id) FROM counter c JOIN zone z ON z.id = c.zone_id JOIN counter_service link ON link.counter_id = c.id"
                        + " WHERE c.active = true AND z.site_id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM counter_session cs WHERE cs.counter_id = c.id AND cs.state IN ('open', 'on_break', 'closing'))"
                        + " AND EXISTS (SELECT 1 FROM ticket t WHERE t.service_id = link.service_id AND t.state IN ('waiting', 'paused', 'remote'))");
        List<Object> args = new ArrayList<>();
        args.add(filter.siteId());
        counterScopeFilters(sql, args, filter);
        Integer count = jdbc.query(sql.toString(), rs -> rs.next() ? rs.getInt(1) : 0, args.toArray());
        return count == null ? 0 : count;
    }

    /** A counter offers a Service, not a Priority class, so only Zone, Service group and Service narrow it. */
    private void counterScopeFilters(StringBuilder sql, List<Object> args, DashboardFilter filter) {
        if (filter.zoneId() != null) {
            sql.append(" AND c.zone_id = ?");
            args.add(filter.zoneId());
        }
        if (filter.serviceId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM counter_service cs2 WHERE cs2.counter_id = c.id AND cs2.service_id = ?)");
            args.add(filter.serviceId());
        } else if (filter.serviceGroupId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM counter_service cs2 JOIN service sv ON sv.id = cs2.service_id WHERE cs2.counter_id = c.id AND sv.service_group_id = ?)");
            args.add(filter.serviceGroupId());
        }
    }

    // ---- longest waits (FR-MON-003, FR-QUE-022) ---------------------------------------------------------------

    record LongestWait(UUID ticketId, String tokenNumber, UUID serviceId, Map<String, String> serviceNames, long waitSeconds, boolean escalated, boolean slaBreached) {}

    List<LongestWait> longestWaits(DashboardFilter filter, List<UUID> allowedGroups, Instant now) {
        StringBuilder sql = new StringBuilder(
                "SELECT t.id, t.token_number, t.service_id, sv.name_i18n AS service_name, sv.sla_wait_minutes,"
                        + " extract(epoch FROM (?::timestamptz - t.queued_at))::bigint AS wait_seconds, pc.max_wait_minutes"
                        + " FROM ticket t JOIN service sv ON sv.id = t.service_id"
                        + " LEFT JOIN priority_class pc ON pc.id = coalesce(t.priority_class_id, (SELECT id FROM priority_class WHERE is_default))"
                        + " WHERE t.state IN ('waiting', 'paused', 'remote')");
        List<Object> args = new ArrayList<>();
        args.add(java.sql.Timestamp.from(now));
        ticketFilters(sql, args, "t", filter, allowedGroups);
        sql.append(" ORDER BY wait_seconds DESC LIMIT 10");
        return jdbc.query(
                sql.toString(),
                (rs, i) -> {
                    long waitSeconds = rs.getLong("wait_seconds");
                    Integer maxWait = rs.getObject("max_wait_minutes", Integer.class);
                    boolean escalated = maxWait != null && waitSeconds > maxWait * 60L;
                    boolean slaBreached = waitSeconds > rs.getInt("sla_wait_minutes") * 60L;
                    return new LongestWait(
                            rs.getObject("id", UUID.class), rs.getString("token_number"), rs.getObject("service_id", UUID.class),
                            names(rs.getString("service_name")), waitSeconds, escalated, slaBreached);
                },
                args.toArray());
    }

    // ---- throughput today (FR-MON-003) ------------------------------------------------------------------------

    record Throughput(int served, int cancelled, int noShow, int transferred) {}

    private static final Map<String, String> THROUGHPUT_EVENTS =
            Map.of("ticket.completed", "served", "ticket.cancelled", "cancelled", "ticket.no_show", "noShow", "ticket.transferred", "transferred");

    Throughput throughputToday(DashboardFilter filter, List<UUID> allowedGroups, Instant todayStart) {
        StringBuilder sql = new StringBuilder(
                "SELECT te.event_type, count(*) AS cnt FROM ticket_event te JOIN ticket t ON t.id = te.ticket_id"
                        + " WHERE te.event_type IN ('ticket.completed', 'ticket.cancelled', 'ticket.no_show', 'ticket.transferred') AND te.occurred_at >= ?");
        List<Object> args = new ArrayList<>();
        args.add(java.sql.Timestamp.from(todayStart));
        ticketFilters(sql, args, "t", filter, allowedGroups);
        sql.append(" GROUP BY te.event_type");
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query(sql.toString(), rs -> {
            counts.put(THROUGHPUT_EVENTS.get(rs.getString("event_type")), rs.getInt("cnt"));
        }, args.toArray());
        return new Throughput(counts.getOrDefault("served", 0), counts.getOrDefault("cancelled", 0), counts.getOrDefault("noShow", 0), counts.getOrDefault("transferred", 0));
    }

    // ---- appointments today (FR-MON-003) ------------------------------------------------------------------------

    record Appointments(int booked, int checkedIn, int noShow, int upcomingNextHour) {}

    Appointments appointmentsToday(DashboardFilter filter, List<UUID> allowedGroups, LocalDate today, ZoneId siteZone, Instant now) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.state, count(*) AS cnt FROM appointment a JOIN service sv ON sv.id = a.service_id"
                        + " JOIN service_group sg ON sg.id = sv.service_group_id WHERE sg.site_id = ? AND a.slot_date = ?");
        List<Object> args = new ArrayList<>();
        args.add(filter.siteId());
        args.add(java.sql.Date.valueOf(today));
        appointmentScopeFilters(sql, args, filter, allowedGroups);
        sql.append(" GROUP BY a.state");
        Map<String, Integer> byState = new LinkedHashMap<>();
        jdbc.query(sql.toString(), rs -> { byState.put(rs.getString("state"), rs.getInt("cnt")); }, args.toArray());
        int booked = byState.getOrDefault("booked", 0) + byState.getOrDefault("rescheduled", 0);
        int checkedIn = byState.getOrDefault("checked_in", 0);
        int noShow = byState.getOrDefault("no_show", 0);

        StringBuilder upcoming = new StringBuilder(
                "SELECT count(*) FROM appointment a JOIN service sv ON sv.id = a.service_id JOIN service_group sg ON sg.id = sv.service_group_id"
                        + " WHERE sg.site_id = ? AND a.slot_date = ? AND a.state IN ('booked', 'checked_in')"
                        + " AND (a.slot_date + a.slot_start) AT TIME ZONE ? BETWEEN ?::timestamptz AND ?::timestamptz + interval '1 hour'");
        List<Object> upcomingArgs = new ArrayList<>();
        upcomingArgs.add(filter.siteId());
        upcomingArgs.add(java.sql.Date.valueOf(today));
        upcomingArgs.add(siteZone.getId());
        upcomingArgs.add(java.sql.Timestamp.from(now));
        upcomingArgs.add(java.sql.Timestamp.from(now));
        appointmentScopeFilters(upcoming, upcomingArgs, filter, allowedGroups);
        Integer upcomingCount = jdbc.query(upcoming.toString(), rs -> rs.next() ? rs.getInt(1) : 0, upcomingArgs.toArray());

        return new Appointments(booked, checkedIn, noShow, upcomingCount == null ? 0 : upcomingCount);
    }

    /** Appointments are not Zone- or Priority-class-scoped (ticket 32-36 never gave a booking either); only Service
     * group and Service, and the reach restriction, narrow them. */
    private void appointmentScopeFilters(StringBuilder sql, List<Object> args, DashboardFilter filter, List<UUID> allowedGroups) {
        if (filter.serviceId() != null) {
            sql.append(" AND sv.id = ?");
            args.add(filter.serviceId());
        } else if (filter.serviceGroupId() != null) {
            sql.append(" AND sg.id = ?");
            args.add(filter.serviceGroupId());
        } else if (allowedGroups != null) {
            appendGroupRestriction(sql, args, "sg", "id", allowedGroups);
        }
    }

    // ---- remote queue (FR-MON-003) ------------------------------------------------------------------------------

    record RemoteQueue(int remote, int approaching, int present, int forfeited) {}

    RemoteQueue remoteQueue(DashboardFilter filter, List<UUID> allowedGroups, Instant todayStart) {
        StringBuilder sql = new StringBuilder(
                "SELECT"
                        + " count(*) FILTER (WHERE t.state = 'remote' AND t.remote_hold_started_at IS NULL) AS remote,"
                        + " count(*) FILTER (WHERE t.state = 'remote' AND t.remote_hold_started_at IS NOT NULL) AS approaching,"
                        + " count(*) FILTER (WHERE t.origin_channel = 'mobile' AND t.state = 'waiting') AS present,"
                        + " count(*) FILTER (WHERE t.state = 'forfeited') AS forfeited"
                        + " FROM ticket t WHERE t.issued_at >= ?");
        List<Object> args = new ArrayList<>();
        args.add(java.sql.Timestamp.from(todayStart));
        ticketFilters(sql, args, "t", filter, allowedGroups);
        return jdbc.query(
                        sql.toString(),
                        (rs, i) -> new RemoteQueue(rs.getInt("remote"), rs.getInt("approaching"), rs.getInt("present"), rs.getInt("forfeited")),
                        args.toArray())
                .stream()
                .findFirst()
                .orElse(new RemoteQueue(0, 0, 0, 0));
    }

    // ---- device health (FR-MON-003) -----------------------------------------------------------------------------

    record DeviceHealth(int kiosksOffline, int displaysOffline, int printersOffline) {}

    DeviceHealth deviceHealth(DashboardFilter filter, Instant now) {
        StringBuilder sql = new StringBuilder(
                "SELECT"
                        + " count(*) FILTER (WHERE kind = 'kiosk' AND (last_heartbeat_at IS NULL OR last_heartbeat_at < ?)) AS kiosks_offline,"
                        + " count(*) FILTER (WHERE kind = 'display' AND (last_heartbeat_at IS NULL OR last_heartbeat_at < ?)) AS displays_offline"
                        + " FROM device WHERE site_id = ? AND active = true");
        List<Object> args = new ArrayList<>();
        java.sql.Timestamp offlineBefore = java.sql.Timestamp.from(now.minus(DEVICE_OFFLINE_AFTER));
        args.add(offlineBefore);
        args.add(offlineBefore);
        args.add(filter.siteId());
        if (filter.zoneId() != null) {
            // A kiosk is Site-scoped, not Zone-scoped (V20): it stays counted under any Zone filter of its own Site.
            sql.append(" AND (zone_id = ? OR kind = 'kiosk')");
            args.add(filter.zoneId());
        }
        return jdbc.query(
                        sql.toString(),
                        (rs, i) -> new DeviceHealth(rs.getInt("kiosks_offline"), rs.getInt("displays_offline"), 0),
                        args.toArray())
                .stream()
                .findFirst()
                .orElse(new DeviceHealth(0, 0, 0));
    }

    // ---- served per open counter (FR-QUE-033) ---------------------------------------------------------------

    /** {@code sessionId} is the live session a supervisor's own "force-close a counter" act (FR-MON-004) needs —
     * {@code POST /sessions/{id}/force-close} already exists (ticket 13); the dashboard only has to hand it the id. */
    record CounterThroughput(UUID counterId, String label, UUID sessionId, int servedCount) {}

    List<CounterThroughput> servedPerOpenCounter(DashboardFilter filter, Instant todayStart) {
        StringBuilder sql = new StringBuilder(
                "SELECT c.id, c.label, cs.id AS session_id,"
                        + " (SELECT count(*) FROM ticket t WHERE t.counter_id = c.id AND t.state = 'completed' AND t.closed_at >= ?) AS served_count"
                        + " FROM counter c JOIN zone z ON z.id = c.zone_id"
                        + " JOIN counter_session cs ON cs.counter_id = c.id AND cs.state IN ('open', 'on_break', 'closing')"
                        + " WHERE c.active = true AND z.site_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(java.sql.Timestamp.from(todayStart));
        args.add(filter.siteId());
        counterScopeFilters(sql, args, filter);
        sql.append(" ORDER BY c.label");
        return jdbc.query(
                sql.toString(),
                (rs, i) -> new CounterThroughput(rs.getObject("id", UUID.class), rs.getString("label"), rs.getObject("session_id", UUID.class), rs.getInt("served_count")),
                args.toArray());
    }

    // ---- active sites (the refresh scheduler's own sweep) ------------------------------------------------------

    List<UUID> activeSiteIds() {
        return jdbc.queryForList("SELECT id FROM site WHERE active = true", UUID.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> names(String json) {
        return json == null ? Map.of() : new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }
}
