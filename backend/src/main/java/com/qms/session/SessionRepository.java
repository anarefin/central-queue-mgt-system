package com.qms.session;

import com.qms.queue.TicketTransition;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL for counter sessions and for the ticket columns a session drives. The counter, service and team tables are read
 * here for what a session must check (SRS §18.2); nothing in this class writes them.
 */
@Repository
class SessionRepository {

    /** The states in which a session still occupies its counter. */
    static final String LIVE = "'open', 'on_break', 'closing'";

    /** A counter with its zone and site. {@code usable} is false while the counter, its zone or its site is deactivated. */
    record CounterRow(UUID id, String label, UUID zoneId, String zoneName, UUID siteId, boolean usable) {}

    /** A Service a counter serves and the caller's team may serve, with the weight of the link. */
    record ServiceLink(UUID serviceId, Map<String, String> names, int weight) {}

    record SessionRow(UUID id, UUID counterId, UUID agentId, Instant openedAt, Instant closedAt, List<UUID> services, String state) {

        boolean live() {
            return "open".equals(state) || "on_break".equals(state) || "closing".equals(state);
        }
    }

    /** A ticket bound to a session. */
    record BoundTicket(
            UUID id,
            String tokenNumber,
            String state,
            int version,
            UUID serviceId,
            Map<String, String> serviceNames,
            String originChannel,
            UUID priorityClassId,
            Map<String, String> priorityClassNames,
            Instant queuedAt,
            Instant calledAt,
            Instant servedAt,
            int announceCount,
            int missCount,
            int scoreAdjustmentMinutes,
            String visitorCode,
            String visitorName,
            String visitorCategory,
            String purposeNote) {}

    /** A ticket named for an out-of-order call (FR-AGT-012): where it is, whether it is free to be called, and who it is meant for. */
    record WaitingTicket(UUID id, String tokenNumber, String state, int version, UUID serviceId, UUID groupId, UUID siteId, UUID sessionId, UUID targetCounterId, UUID targetAgentId) {}

    /** A called ticket whose Agent has not acted in time and has now been prompted (FR-QUE-032). */
    record TimedOutCall(UUID ticketId, String tokenNumber, UUID serviceId, UUID sessionId, UUID counterId, int version) {}

    /** The Service group and site a Service belongs to, which decide who may watch its queue. */
    record ServiceScope(UUID serviceId, UUID groupId, UUID siteId) {}

    record OutcomeRow(UUID id, String code, Map<String, String> labels) {}

    /** A ticket as a transfer sees it: its state, its Session binding (null unless it is bound) and where it is queued. */
    record TransferSource(
            UUID id,
            String tokenNumber,
            String state,
            int version,
            UUID sessionId,
            UUID counterId,
            UUID serviceId,
            UUID groupId,
            UUID siteId,
            Instant queuedAt,
            Instant servedAt) {}

    /** A Service a ticket may be transferred to. {@code active} means the Service, its group and its site are all active. */
    record TransferService(UUID id, Map<String, String> names, UUID groupId, UUID siteId, boolean active) {}

    /** An Agent a ticket may be targeted at: {@code activeAtSite} is false when the account is disabled or not allowed at the site. */
    record TransferAgent(UUID id, String name, boolean active, boolean atSite) {}

    /** A counter in the site with the Services it serves, for the transfer target list. */
    record TargetCounter(UUID id, String label, String zoneName, List<UUID> serviceIds) {}

    /** An agent of the site with the Services of the groups whose team they are on, for the transfer target list. */
    record TargetAgent(UUID id, String name, List<UUID> serviceIds) {}

    private static final String BOUND =
            "SELECT t.id, t.token_number, t.state, t.version, t.service_id, v.name_i18n AS service_names, t.origin_channel,"
                    + " pc.id AS class_id, pc.name_i18n AS class_names, t.queued_at, t.called_at, t.served_at,"
                    + " t.announce_count, t.miss_count, t.score_adjustment_minutes,"
                    + " vi.external_code AS visitor_code, vi.name AS visitor_name, vi.category AS visitor_category, t.purpose_note"
                    + " FROM ticket t JOIN service v ON v.id = t.service_id LEFT JOIN visitor vi ON vi.id = t.visitor_id"
                    + " LEFT JOIN priority_class pc ON pc.id = coalesce(t.priority_class_id, (SELECT id FROM priority_class WHERE is_default))";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    SessionRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---- counters and what an agent may occupy ----------------------------------------------------------------

    Optional<CounterRow> counter(UUID id) {
        return jdbc.query(
                        "SELECT c.id, c.label, z.id AS zone_id, z.name AS zone_name, z.site_id, (c.active AND z.active AND s.active) AS usable"
                                + " FROM counter c JOIN zone z ON z.id = c.zone_id JOIN site s ON s.id = z.site_id WHERE c.id = ?",
                        (rs, i) -> counter(rs),
                        id)
                .stream().findFirst();
    }

    /**
     * The Services of a counter that a user's team may serve: active Services of active groups linked to the counter, in
     * primary-first order. A user is on the team of a Service group through {@code team_member} (FR-CFG-011, CONTEXT.md);
     * that membership is what makes a counter one they are permitted to occupy (FR-AGT-001).
     */
    List<ServiceLink> permittedServices(UUID counterId, UUID userId) {
        return jdbc.query(
                "SELECT v.id, v.name_i18n, cs.preference_weight FROM counter_service cs JOIN service v ON v.id = cs.service_id"
                        + " JOIN service_group g ON g.id = v.service_group_id JOIN team t ON t.service_group_id = g.id"
                        + " JOIN team_member m ON m.team_id = t.id AND m.user_id = ?"
                        + " WHERE cs.counter_id = ? AND v.active AND g.active ORDER BY cs.preference_weight, v.display_order, v.id",
                (rs, i) -> new ServiceLink(rs.getObject("id", UUID.class), names(rs.getString("name_i18n")), rs.getInt("preference_weight")),
                userId, counterId);
    }

    /** Every usable counter the user has at least one permitted Service on, with those Services and whether it is occupied. */
    List<CounterOptions.Item> permittedCounters(UUID userId) {
        record Row(CounterRow counter, boolean occupied, ServiceLink link) {}
        List<Row> rows = jdbc.query(
                "SELECT c.id, c.label, z.id AS zone_id, z.name AS zone_name, z.site_id, (c.active AND z.active AND s.active) AS usable,"
                        + " EXISTS (SELECT 1 FROM counter_session cs2 WHERE cs2.counter_id = c.id AND cs2.state IN (" + LIVE + ")) AS occupied,"
                        + " v.id AS service_id, v.name_i18n, cs.preference_weight"
                        + " FROM counter c JOIN zone z ON z.id = c.zone_id JOIN site s ON s.id = z.site_id"
                        + " JOIN counter_service cs ON cs.counter_id = c.id JOIN service v ON v.id = cs.service_id"
                        + " JOIN service_group g ON g.id = v.service_group_id JOIN team t ON t.service_group_id = g.id"
                        + " JOIN team_member m ON m.team_id = t.id AND m.user_id = ?"
                        + " WHERE c.active AND z.active AND s.active AND v.active AND g.active"
                        + " ORDER BY lower(z.name), z.id, lower(c.label), c.id, cs.preference_weight, v.display_order, v.id",
                (rs, i) -> new Row(
                        counter(rs),
                        rs.getBoolean("occupied"),
                        new ServiceLink(rs.getObject("service_id", UUID.class), names(rs.getString("name_i18n")), rs.getInt("preference_weight"))),
                userId);
        Map<UUID, CounterRow> counters = new LinkedHashMap<>();
        Map<UUID, Boolean> occupied = new LinkedHashMap<>();
        Map<UUID, List<ServiceLink>> links = new LinkedHashMap<>();
        for (Row row : rows) {
            counters.putIfAbsent(row.counter().id(), row.counter());
            occupied.put(row.counter().id(), row.occupied());
            links.computeIfAbsent(row.counter().id(), k -> new ArrayList<>()).add(row.link());
        }
        List<CounterOptions.Item> items = new ArrayList<>();
        counters.forEach((id, counter) -> items.add(new CounterOptions.Item(
                SessionViews.counter(counter), occupied.get(id), links.get(id).stream().map(SessionViews::service).toList())));
        return items;
    }

    Optional<ServiceScope> serviceScope(UUID serviceId) {
        return jdbc.query(
                        "SELECT v.id, g.id AS group_id, g.site_id FROM service v JOIN service_group g ON g.id = v.service_group_id WHERE v.id = ?",
                        (rs, i) -> new ServiceScope(rs.getObject("id", UUID.class), rs.getObject("group_id", UUID.class), rs.getObject("site_id", UUID.class)),
                        serviceId)
                .stream().findFirst();
    }

    /** Whether the user is on the team of the Service group (CONTEXT.md), which is what lets an Agent serve its Services. */
    boolean onTeamOf(UUID groupId, UUID userId) {
        Boolean member = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM team t JOIN team_member m ON m.team_id = t.id WHERE t.service_group_id = ? AND m.user_id = ?)", Boolean.class, groupId, userId);
        return Boolean.TRUE.equals(member);
    }

    /** The links of a counter to the given Services, active only, as they are now (the links may have been changed). */
    List<ServiceLink> links(UUID counterId, Collection<UUID> serviceIds) {
        return jdbc.query(
                connection -> {
                    var ps = connection.prepareStatement(
                            "SELECT v.id, v.name_i18n, cs.preference_weight FROM counter_service cs JOIN service v ON v.id = cs.service_id"
                                    + " JOIN service_group g ON g.id = v.service_group_id"
                                    + " WHERE cs.counter_id = ? AND cs.service_id = ANY (?) AND v.active AND g.active ORDER BY cs.preference_weight, v.display_order, v.id");
                    ps.setObject(1, counterId);
                    ps.setArray(2, connection.createArrayOf("uuid", serviceIds.toArray()));
                    return ps;
                },
                (rs, i) -> new ServiceLink(rs.getObject("id", UUID.class), names(rs.getString("name_i18n")), rs.getInt("preference_weight")));
    }

    // ---- sessions ---------------------------------------------------------------------------------------------

    void insertSession(UUID id, UUID counterId, UUID agentId, Instant openedAt, List<UUID> services) {
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO counter_session (id, counter_id, agent_id, opened_at, services, state) VALUES (?, ?, ?, ?, ?, 'open')");
            ps.setObject(1, id);
            ps.setObject(2, counterId);
            ps.setObject(3, agentId);
            ps.setObject(4, ts(openedAt));
            ps.setArray(5, connection.createArrayOf("uuid", services.toArray()));
            return ps;
        });
    }

    Optional<SessionRow> liveSessionOfAgent(UUID agentId) {
        return jdbc.query("SELECT * FROM counter_session WHERE agent_id = ? AND state IN (" + LIVE + ")", (rs, i) -> session(rs), agentId).stream().findFirst();
    }

    Optional<SessionRow> liveSessionOfCounter(UUID counterId) {
        return jdbc.query("SELECT * FROM counter_session WHERE counter_id = ? AND state IN (" + LIVE + ")", (rs, i) -> session(rs), counterId).stream().findFirst();
    }

    /** The row, locked until the transaction ends, so that one session's actions never interleave. */
    Optional<SessionRow> lock(UUID id) {
        return jdbc.query("SELECT * FROM counter_session WHERE id = ? FOR UPDATE", (rs, i) -> session(rs), id).stream().findFirst();
    }

    Optional<SessionRow> session(UUID id) {
        return jdbc.query("SELECT * FROM counter_session WHERE id = ?", (rs, i) -> session(rs), id).stream().findFirst();
    }

    void setState(UUID id, String state, Instant closedAt) {
        jdbc.update("UPDATE counter_session SET state = ?, closed_at = coalesce(?, closed_at) WHERE id = ?", state, closedAt == null ? null : ts(closedAt), id);
    }

    // ---- breaks -----------------------------------------------------------------------------------------------

    /** A break type as a session sees it: its names and longest duration, and whether it may still be picked. */
    record BreakTypeRow(UUID id, Map<String, String> names, Integer maxMinutes, boolean active) {}

    /** The break a session is on now. */
    record OpenBreak(UUID id, UUID typeId, Map<String, String> typeNames, Integer maxMinutes, Instant startedAt) {}

    Optional<BreakTypeRow> breakType(UUID id) {
        return jdbc.query(
                        "SELECT id, name_i18n, max_minutes, active FROM break_type WHERE id = ?",
                        (rs, i) -> new BreakTypeRow(rs.getObject("id", UUID.class), names(rs.getString("name_i18n")), rs.getObject("max_minutes", Integer.class), rs.getBoolean("active")),
                        id)
                .stream().findFirst();
    }

    /** The break the session is on: the record with no end. There is at most one (a unique index says so). */
    Optional<OpenBreak> openBreak(UUID sessionId) {
        return jdbc.query(
                        "SELECT r.id, r.break_type_id, t.name_i18n, t.max_minutes, r.started_at FROM break_record r JOIN break_type t ON t.id = r.break_type_id"
                                + " WHERE r.counter_session_id = ? AND r.ended_at IS NULL",
                        (rs, i) -> new OpenBreak(
                                rs.getObject("id", UUID.class), rs.getObject("break_type_id", UUID.class), names(rs.getString("name_i18n")), rs.getObject("max_minutes", Integer.class), instant(rs, "started_at")),
                        sessionId)
                .stream().findFirst();
    }

    void insertBreak(UUID id, UUID sessionId, UUID typeId, Instant startedAt, UUID startedBy) {
        jdbc.update(
                "INSERT INTO break_record (id, counter_session_id, break_type_id, started_at, started_by) VALUES (?, ?, ?, ?, ?)", id, sessionId, typeId, ts(startedAt), startedBy);
    }

    void endBreak(UUID id, Instant endedAt, UUID endedBy) {
        jdbc.update("UPDATE break_record SET ended_at = ?, ended_by = ? WHERE id = ?", ts(endedAt), endedBy, id);
    }

    /** Every session that still occupies its counter, oldest first. */
    List<SessionRow> liveSessions() {
        return jdbc.query("SELECT * FROM counter_session WHERE state IN (" + LIVE + ") ORDER BY opened_at, id", (rs, i) -> session(rs));
    }

    /** What a person is called on screen: their display name, else their username. */
    String userName(UUID userId) {
        return jdbc.query("SELECT coalesce(nullif(display_name, ''), username) AS name FROM users WHERE id = ?", (rs, i) -> rs.getString("name"), userId).stream().findFirst().orElse(null);
    }

    boolean userExists(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)", Boolean.class, userId));
    }

    /** An ended break with the site and Service groups of its session, which decide who may report on it. */
    record EndedBreak(BreakReport.Taken taken, UUID siteId, List<UUID> groupIds) {}

    /** The breaks that ended, started within [from, to) when given, for one agent and one type when given. */
    List<EndedBreak> endedBreaks(Instant from, Instant to, UUID agentId, UUID typeId) {
        StringBuilder sql = new StringBuilder(
                "SELECT s.agent_id, coalesce(nullif(u.display_name, ''), u.username) AS agent_name, t.id AS type_id, t.name_i18n, t.max_minutes, r.started_at, r.ended_at, z.site_id,"
                        + " coalesce((SELECT array_agg(DISTINCT v.service_group_id) FROM service v WHERE v.id = ANY (s.services)), '{}') AS group_ids"
                        + " FROM break_record r JOIN counter_session s ON s.id = r.counter_session_id JOIN users u ON u.id = s.agent_id"
                        + " JOIN break_type t ON t.id = r.break_type_id JOIN counter c ON c.id = s.counter_id JOIN zone z ON z.id = c.zone_id"
                        + " WHERE r.ended_at IS NOT NULL");
        List<Object> arguments = new ArrayList<>();
        if (from != null) {
            sql.append(" AND r.started_at >= ?");
            arguments.add(ts(from));
        }
        if (to != null) {
            sql.append(" AND r.started_at < ?");
            arguments.add(ts(to));
        }
        if (agentId != null) {
            sql.append(" AND s.agent_id = ?");
            arguments.add(agentId);
        }
        if (typeId != null) {
            sql.append(" AND r.break_type_id = ?");
            arguments.add(typeId);
        }
        sql.append(" ORDER BY r.started_at, r.id");
        return jdbc.query(
                sql.toString(),
                (rs, i) -> new EndedBreak(
                        new BreakReport.Taken(
                                rs.getObject("agent_id", UUID.class),
                                rs.getString("agent_name"),
                                rs.getObject("type_id", UUID.class),
                                names(rs.getString("name_i18n")),
                                rs.getObject("max_minutes", Integer.class),
                                instant(rs, "started_at"),
                                instant(rs, "ended_at")),
                        rs.getObject("site_id", UUID.class),
                        List.of((UUID[]) rs.getArray("group_ids").getArray())),
                arguments.toArray());
    }

    /** The tickets an Agent completed today, their average service time in whole seconds (null when none), and their break time in seconds. */
    record DayFigures(int served, Integer averageServiceSeconds, long breakSeconds) {}

    /**
     * The Agent's own day (FR-AGT-040). A ticket or break belongs to the day, in its Site's time zone, that {@code now} falls in there. Tickets count
     * once completed; a break counts from its start to its end, or to {@code now} while it runs.
     */
    DayFigures dayFigures(UUID agentId, Instant now) {
        OffsetDateTime at = ts(now);
        return jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM ticket t JOIN site s ON s.id = t.site_id"
                        + "   WHERE t.agent_id = ? AND t.state = 'completed' AND (t.closed_at AT TIME ZONE s.timezone)::date = (?::timestamptz AT TIME ZONE s.timezone)::date) AS served,"
                        + " (SELECT round(avg(t.service_seconds))::integer FROM ticket t JOIN site s ON s.id = t.site_id"
                        + "   WHERE t.agent_id = ? AND t.state = 'completed' AND (t.closed_at AT TIME ZONE s.timezone)::date = (?::timestamptz AT TIME ZONE s.timezone)::date) AS average,"
                        + " (SELECT coalesce(round(sum(extract(epoch FROM (coalesce(r.ended_at, ?::timestamptz) - r.started_at)))), 0)::bigint"
                        + "   FROM break_record r JOIN counter_session cs ON cs.id = r.counter_session_id JOIN counter c ON c.id = cs.counter_id"
                        + "   JOIN zone z ON z.id = c.zone_id JOIN site s ON s.id = z.site_id"
                        + "   WHERE cs.agent_id = ? AND (r.started_at AT TIME ZONE s.timezone)::date = (?::timestamptz AT TIME ZONE s.timezone)::date) AS break_seconds",
                (rs, i) -> new DayFigures(rs.getInt("served"), rs.getObject("average", Integer.class), rs.getLong("break_seconds")),
                agentId, at, agentId, at, at, agentId, at);
    }

    // ---- the tickets a session drives -------------------------------------------------------------------------

    /** The tickets bound to the session that are not finished: called, serving or held (ADR-0008). */
    List<BoundTicket> unresolved(UUID sessionId) {
        return jdbc.query(
                BOUND + " WHERE t.counter_session_id = ? AND t.state IN ('called', 'serving', 'held') ORDER BY t.called_at, t.queued_at, t.id",
                (rs, i) -> bound(rs),
                sessionId);
    }

    /** Binds a waiting ticket to a session under optimistic concurrency on its version; false when someone else got there first. */
    boolean call(UUID ticketId, int version, UUID sessionId, UUID counterId, UUID agentId, Instant now) {
        return jdbc.update(
                "UPDATE ticket SET state = ?, counter_session_id = ?, counter_id = ?, agent_id = ?, called_at = ?, version = version + 1"
                        + " WHERE id = ? AND version = ? AND state = ? AND counter_session_id IS NULL"
                        // A ticket targeted at another counter or agent is not this session's to draw (FR-QUE-003).
                        + " AND (target_counter_id IS NULL OR target_counter_id = ?) AND (target_agent_id IS NULL OR target_agent_id = ?)",
                TicketTransition.CALL.to(), sessionId, counterId, agentId, ts(now), ticketId, version, TicketTransition.CALL.from(), counterId, agentId) == 1;
    }

    boolean startService(UUID ticketId, int version, UUID sessionId, Instant now) {
        return jdbc.update(
                "UPDATE ticket SET state = ?, served_at = ?, version = version + 1 WHERE id = ? AND version = ? AND state = ? AND counter_session_id = ?",
                TicketTransition.START_SERVICE.to(), ts(now), ticketId, version, TicketTransition.START_SERVICE.from(), sessionId) == 1;
    }

    /** Closes a served ticket: the binding is cleared with the terminal state, the counter and agent stay as history. */
    boolean complete(UUID ticketId, int version, UUID sessionId, Instant now, int waitSeconds, int serviceSeconds, UUID outcomeCodeId, String note) {
        return jdbc.update(
                "UPDATE ticket SET state = ?, counter_session_id = NULL, closed_at = ?, wait_seconds = ?, service_seconds = ?, outcome_code_id = ?,"
                        + " note = ?, version = version + 1 WHERE id = ? AND version = ? AND state = ? AND counter_session_id = ?",
                TicketTransition.COMPLETE.to(), ts(now), waitSeconds, serviceSeconds, outcomeCodeId, note, ticketId, version,
                TicketTransition.COMPLETE.from(), sessionId) == 1;
    }

    /** Replays the call: the ticket stays called, keeps its binding and counts one more announcement (ADR-0005). */
    boolean reannounce(UUID ticketId, int version, UUID sessionId) {
        return jdbc.update(
                "UPDATE ticket SET announce_count = announce_count + 1, version = version + 1 WHERE id = ? AND version = ? AND state = ? AND counter_session_id = ?",
                ticketId, version, TicketTransition.REANNOUNCE.from(), sessionId) == 1;
    }

    /**
     * Returns a called ticket to the queue: the binding is cleared (Invariant 2), the miss is counted and the Score
     * adjustment is set to where the ticket re-enters. {@code queued_at} is left alone (ADR-0004); the counter and agent
     * stay as history, and {@code announce_count} is never reset, so an announcement stays unique to its ticket (FR-QUE-083).
     */
    boolean miss(UUID ticketId, int version, UUID sessionId, int scoreAdjustmentMinutes) {
        return jdbc.update(
                "UPDATE ticket SET state = ?, counter_session_id = NULL, miss_count = miss_count + 1,"
                        + " score_adjustment_minutes = ?, version = version + 1 WHERE id = ? AND version = ? AND state = ? AND counter_session_id = ?",
                TicketTransition.MISS.to(), scoreAdjustmentMinutes, ticketId, version, TicketTransition.MISS.from(), sessionId) == 1;
    }

    /** Closes a called ticket whose Miss went past the limit; like any terminal state it clears the binding. */
    boolean noShow(UUID ticketId, int version, UUID sessionId, Instant now, int waitSeconds) {
        return jdbc.update(
                "UPDATE ticket SET state = ?, counter_session_id = NULL, miss_count = miss_count + 1, closed_at = ?, wait_seconds = ?,"
                        + " version = version + 1 WHERE id = ? AND version = ? AND state = ? AND counter_session_id = ?",
                TicketTransition.NO_SHOW.to(), ts(now), waitSeconds, ticketId, version, TicketTransition.NO_SHOW.from(), sessionId) == 1;
    }

    /** Parks the serving ticket: it stays bound to the session and keeps its counter, agent and service clock (ADR-0008). */
    boolean hold(UUID ticketId, int version, UUID sessionId) {
        return jdbc.update(
                "UPDATE ticket SET state = ?, version = version + 1 WHERE id = ? AND version = ? AND state = ? AND counter_session_id = ?",
                TicketTransition.HOLD.to(), ticketId, version, TicketTransition.HOLD.from(), sessionId) == 1;
    }

    /** Puts a held ticket back in service; only the session that holds it can (ADR-0008). */
    boolean resume(UUID ticketId, int version, UUID sessionId) {
        return jdbc.update(
                "UPDATE ticket SET state = ?, version = version + 1 WHERE id = ? AND version = ? AND state = ? AND counter_session_id = ?",
                TicketTransition.RESUME.to(), ticketId, version, TicketTransition.RESUME.from(), sessionId) == 1;
    }

    /**
     * Takes a called, serving or held ticket out of a force-closed session and back to the queue (ADR-0008): the binding is
     * cleared, the Score adjustment puts it at the front, and {@code queued_at} is left alone (ADR-0004). Not a Miss, so
     * {@code miss_count} is untouched.
     */
    boolean returnToQueue(UUID ticketId, int version, UUID sessionId, TicketTransition transition, int scoreAdjustmentMinutes) {
        return jdbc.update(
                "UPDATE ticket SET state = ?, counter_session_id = NULL, score_adjustment_minutes = ?, version = version + 1"
                        + " WHERE id = ? AND version = ? AND state = ? AND counter_session_id = ?",
                transition.to(), scoreAdjustmentMinutes, ticketId, version, transition.from(), sessionId) == 1;
    }

    // ---- staff actions on a ticket: change of class and cancel (FR-QUE-012, SRS §19.1) --------------------------

    /** A ticket as staff act on it: where it is queued or bound, whom it is meant for, and the class it carries (null is the default class). */
    record ActionTicket(
            UUID id,
            String tokenNumber,
            String state,
            int version,
            UUID serviceId,
            UUID groupId,
            UUID siteId,
            UUID sessionId,
            UUID counterId,
            UUID targetAgentId,
            UUID priorityClassId,
            Instant queuedAt,
            Instant servedAt) {}

    /** The ticket, locked until the transaction ends when {@code lock} is set, so a call or a second action cannot interleave with a write. */
    Optional<ActionTicket> actionTicket(UUID ticketId, boolean lock) {
        return jdbc.query(
                        "SELECT id, token_number, state, version, service_id, service_group_id, site_id, counter_session_id, counter_id, target_agent_id,"
                                + " priority_class_id, queued_at, served_at FROM ticket WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                        (rs, i) -> new ActionTicket(
                                rs.getObject("id", UUID.class),
                                rs.getString("token_number"),
                                rs.getString("state"),
                                rs.getInt("version"),
                                rs.getObject("service_id", UUID.class),
                                rs.getObject("service_group_id", UUID.class),
                                rs.getObject("site_id", UUID.class),
                                rs.getObject("counter_session_id", UUID.class),
                                rs.getObject("counter_id", UUID.class),
                                rs.getObject("target_agent_id", UUID.class),
                                rs.getObject("priority_class_id", UUID.class),
                                instant(rs, "queued_at"),
                                instant(rs, "served_at")),
                        ticketId)
                .stream().findFirst();
    }

    /** A Priority class as a change of class needs it. */
    record ClassRow(UUID id, boolean active, boolean isDefault) {}

    Optional<ClassRow> priorityClass(UUID id) {
        return jdbc.query(
                        "SELECT id, active, is_default FROM priority_class WHERE id = ?",
                        (rs, i) -> new ClassRow(rs.getObject("id", UUID.class), rs.getBoolean("active"), rs.getBoolean("is_default")),
                        id)
                .stream().findFirst();
    }

    /** The default (normal) class: the one a ticket with no class of its own belongs to. */
    UUID defaultClassId() {
        return jdbc.queryForObject("SELECT id FROM priority_class WHERE is_default", UUID.class);
    }

    /**
     * Gives a waiting ticket another class (null is the default class). Nothing else about the ticket changes: its wait and its
     * Score adjustment are its own, so it moves by the difference of the Head starts only (ADR-0003, ADR-0004).
     */
    boolean reprioritise(UUID ticketId, int version, UUID priorityClassId) {
        return jdbc.update(
                "UPDATE ticket SET priority_class_id = ?, version = version + 1 WHERE id = ? AND version = ? AND state = 'waiting'",
                priorityClassId, ticketId, version) == 1;
    }

    /**
     * Closes an active ticket as {@code cancelled}: like any terminal state it clears the binding, and the counter and agent stay as
     * history. Its wait is stored now and, if service had started, so is its service time (§18.5).
     */
    boolean cancel(UUID ticketId, int version, Instant now, int waitSeconds, Integer serviceSeconds) {
        return jdbc.update(
                "UPDATE ticket SET state = ?, counter_session_id = NULL, closed_at = ?, wait_seconds = ?, service_seconds = ?, version = version + 1"
                        + " WHERE id = ? AND version = ? AND state IN ('remote', 'waiting', 'paused', 'called', 'serving', 'held')",
                TicketTransition.CANCELLED, ts(now), waitSeconds, serviceSeconds, ticketId, version) == 1;
    }

    /** The ticket an agent names for an out-of-order call, wherever it is now; empty when there is no such ticket. */
    Optional<WaitingTicket> waitingTicket(UUID ticketId) {
        return jdbc.query(
                        "SELECT id, token_number, state, version, service_id, service_group_id, site_id, counter_session_id, target_counter_id, target_agent_id FROM ticket WHERE id = ?",
                        (rs, i) -> new WaitingTicket(
                                rs.getObject("id", UUID.class),
                                rs.getString("token_number"),
                                rs.getString("state"),
                                rs.getInt("version"),
                                rs.getObject("service_id", UUID.class),
                                rs.getObject("service_group_id", UUID.class),
                                rs.getObject("site_id", UUID.class),
                                rs.getObject("counter_session_id", UUID.class),
                                rs.getObject("target_counter_id", UUID.class),
                                rs.getObject("target_agent_id", UUID.class)),
                        ticketId)
                .stream().findFirst();
    }

    /**
     * How many tickets a counter may have in progress for each of the Services (FR-AGT-010, FR-AGT-011): the Service's maximum when it
     * serves in parallel, else one.
     */
    Map<UUID, Integer> concurrencyLimits(Collection<UUID> serviceIds) {
        Map<UUID, Integer> limits = new LinkedHashMap<>();
        jdbc.query(
                connection -> {
                    var ps = connection.prepareStatement("SELECT id, parallel_serving, parallel_limit FROM service WHERE id = ANY (?)");
                    ps.setArray(1, connection.createArrayOf("uuid", serviceIds.toArray()));
                    return ps;
                },
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> limits.put(
                        rs.getObject("id", UUID.class), com.qms.queue.CallRules.limit(rs.getBoolean("parallel_serving"), rs.getInt("parallel_limit"))));
        return limits;
    }

    /**
     * Claims the calls that have gone unanswered for {@code timeoutSeconds}: each called ticket whose Agent has not yet been prompted
     * for this call is marked as prompted and returned. The mark is what keeps the prompt to once per call however many nodes run
     * the check (ADR-0010). The ticket's state and version are left alone: a prompt is not a transition (Invariant 3).
     */
    List<TimedOutCall> claimTimedOutCalls(Instant now, int timeoutSeconds) {
        return jdbc.query(
                "UPDATE ticket SET call_timeout_notified_at = ? WHERE state = 'called' AND counter_session_id IS NOT NULL AND called_at <= ?"
                        + " AND (call_timeout_notified_at IS NULL OR call_timeout_notified_at <= called_at)"
                        + " RETURNING id, token_number, service_id, counter_session_id, counter_id, version",
                (rs, i) -> new TimedOutCall(
                        rs.getObject("id", UUID.class),
                        rs.getString("token_number"),
                        rs.getObject("service_id", UUID.class),
                        rs.getObject("counter_session_id", UUID.class),
                        rs.getObject("counter_id", UUID.class),
                        rs.getInt("version")),
                ts(now), ts(now.minusSeconds(timeoutSeconds)));
    }

    Integer versionOf(UUID ticketId) {
        return jdbc.query("SELECT version FROM ticket WHERE id = ?", (rs, i) -> rs.getInt("version"), ticketId).stream().findFirst().orElse(null);
    }

    /** The outcomes an agent may record for a Service, in display order. */
    List<OutcomeRow> outcomes(UUID serviceId) {
        return jdbc.query(
                "SELECT id, code, label_i18n FROM outcome_code WHERE service_id = ? AND active ORDER BY display_order, lower(code), id",
                (rs, i) -> new OutcomeRow(rs.getObject("id", UUID.class), rs.getString("code"), names(rs.getString("label_i18n"))),
                serviceId);
    }

    // ---- transfer (ticket 15) ---------------------------------------------------------------------------------

    Optional<TransferSource> transferSource(UUID ticketId) {
        return jdbc.query(
                        "SELECT id, token_number, state, version, counter_session_id, counter_id, service_id, service_group_id, site_id, queued_at, served_at"
                                + " FROM ticket WHERE id = ?",
                        (rs, i) -> new TransferSource(
                                rs.getObject("id", UUID.class),
                                rs.getString("token_number"),
                                rs.getString("state"),
                                rs.getInt("version"),
                                rs.getObject("counter_session_id", UUID.class),
                                rs.getObject("counter_id", UUID.class),
                                rs.getObject("service_id", UUID.class),
                                rs.getObject("service_group_id", UUID.class),
                                rs.getObject("site_id", UUID.class),
                                instant(rs, "queued_at"),
                                instant(rs, "served_at")),
                        ticketId)
                .stream().findFirst();
    }

    UUID visitOf(UUID ticketId) {
        return jdbc.queryForObject("SELECT visit_id FROM ticket WHERE id = ?", UUID.class, ticketId);
    }

    Optional<TransferService> transferService(UUID serviceId) {
        return jdbc.query(
                        "SELECT v.id, v.name_i18n, g.id AS group_id, g.site_id, (v.active AND g.active AND s.active) AS active"
                                + " FROM service v JOIN service_group g ON g.id = v.service_group_id JOIN site s ON s.id = g.site_id WHERE v.id = ?",
                        (rs, i) -> new TransferService(
                                rs.getObject("id", UUID.class), names(rs.getString("name_i18n")), rs.getObject("group_id", UUID.class), rs.getObject("site_id", UUID.class), rs.getBoolean("active")),
                        serviceId)
                .stream().findFirst();
    }

    boolean counterServes(UUID counterId, UUID serviceId) {
        Boolean serves = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM counter_service WHERE counter_id = ? AND service_id = ?)", Boolean.class, counterId, serviceId);
        return Boolean.TRUE.equals(serves);
    }

    /** The account of an Agent and whether a role of theirs lets them work at the site (an assignment with no sites covers all of them). */
    Optional<TransferAgent> transferAgent(UUID userId, UUID siteId) {
        return jdbc.query(
                        "SELECT u.id, coalesce(u.display_name, u.username) AS name, u.active,"
                                + " EXISTS (SELECT 1 FROM role_assignments r WHERE r.user_id = u.id AND r.role = 'agent'"
                                + " AND (cardinality(r.site_ids) = 0 OR ? = ANY (r.site_ids))) AS at_site"
                                + " FROM users u WHERE u.id = ?",
                        (rs, i) -> new TransferAgent(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("active"), rs.getBoolean("at_site")),
                        siteId, userId)
                .stream().findFirst();
    }

    /** Services a ticket of a site may be transferred to: active Services of active groups, in display order. */
    List<TransferService> transferServices(UUID siteId) {
        return jdbc.query(
                "SELECT v.id, v.name_i18n, g.id AS group_id, g.site_id, true AS active FROM service v JOIN service_group g ON g.id = v.service_group_id"
                        + " WHERE g.site_id = ? AND v.active AND g.active ORDER BY g.display_order, g.token_prefix, v.display_order, v.token_prefix, v.id",
                (rs, i) -> new TransferService(
                        rs.getObject("id", UUID.class), names(rs.getString("name_i18n")), rs.getObject("group_id", UUID.class), rs.getObject("site_id", UUID.class), true),
                siteId);
    }

    /** Usable counters of a site with the active Services each serves. */
    List<TargetCounter> targetCounters(UUID siteId) {
        record Row(UUID id, String label, String zone, UUID service) {}
        List<Row> rows = jdbc.query(
                "SELECT c.id, c.label, z.name AS zone_name, cs.service_id FROM counter c JOIN zone z ON z.id = c.zone_id JOIN site s ON s.id = z.site_id"
                        + " JOIN counter_service cs ON cs.counter_id = c.id JOIN service v ON v.id = cs.service_id JOIN service_group g ON g.id = v.service_group_id"
                        + " WHERE z.site_id = ? AND c.active AND z.active AND s.active AND v.active AND g.active"
                        + " ORDER BY lower(z.name), z.id, lower(c.label), c.id, cs.preference_weight, v.display_order, v.id",
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("label"), rs.getString("zone_name"), rs.getObject("service_id", UUID.class)),
                siteId);
        Map<UUID, TargetCounter> counters = new LinkedHashMap<>();
        for (Row row : rows) {
            counters.computeIfAbsent(row.id(), id -> new TargetCounter(id, row.label(), row.zone(), new ArrayList<>())).serviceIds().add(row.service());
        }
        return List.copyOf(counters.values());
    }

    /** Active agents who work at the site and are on the team of an active group of it, with that group's active Services. */
    List<TargetAgent> targetAgents(UUID siteId) {
        record Row(UUID id, String name, UUID service) {}
        List<Row> rows = jdbc.query(
                "SELECT u.id, coalesce(u.display_name, u.username) AS name, v.id AS service_id FROM users u"
                        + " JOIN team_member m ON m.user_id = u.id JOIN team t ON t.id = m.team_id JOIN service_group g ON g.id = t.service_group_id"
                        + " JOIN service v ON v.service_group_id = g.id"
                        + " WHERE g.site_id = ? AND u.active AND g.active AND v.active"
                        + " AND EXISTS (SELECT 1 FROM role_assignments r WHERE r.user_id = u.id AND r.role = 'agent' AND (cardinality(r.site_ids) = 0 OR g.site_id = ANY (r.site_ids)))"
                        + " ORDER BY lower(coalesce(u.display_name, u.username)), u.id, v.display_order, v.id",
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getObject("service_id", UUID.class)),
                siteId);
        Map<UUID, TargetAgent> agents = new LinkedHashMap<>();
        for (Row row : rows) {
            agents.computeIfAbsent(row.id(), id -> new TargetAgent(id, row.name(), new ArrayList<>())).serviceIds().add(row.service());
        }
        return List.copyOf(agents.values());
    }

    /** The zone a ticket for this Service waits in: that of its primary active counter, or null when no active counter serves it (as at issue). */
    UUID waitingZone(UUID serviceId) {
        return jdbc.query(
                        "SELECT z.id FROM counter_service cs JOIN counter c ON c.id = cs.counter_id JOIN zone z ON z.id = c.zone_id"
                                + " WHERE cs.service_id = ? AND c.active AND z.active ORDER BY cs.preference_weight, z.display_order, z.id LIMIT 1",
                        (rs, i) -> rs.getObject("id", UUID.class),
                        serviceId)
                .stream().findFirst().orElse(null);
    }

    /**
     * Closes the ticket being served as {@code transferred}: like any terminal state it clears the binding, and the counter and
     * agent stay as history. Its wait and service time are stored now (§18.5) and the transfer note is kept with it.
     */
    boolean transfer(UUID ticketId, int version, UUID sessionId, Instant now, int waitSeconds, int serviceSeconds, String note) {
        return jdbc.update(
                "UPDATE ticket SET state = ?, counter_session_id = NULL, closed_at = ?, wait_seconds = ?, service_seconds = ?, note = ?,"
                        + " version = version + 1 WHERE id = ? AND version = ? AND state = ? AND counter_session_id = ?",
                TicketTransition.TRANSFER.to(), ts(now), waitSeconds, serviceSeconds, note, ticketId, version, TicketTransition.TRANSFER.from(), sessionId) == 1;
    }

    /**
     * Creates the successor of a transferred ticket (ADR-0006): the same Visit, Token number, sequence and reset period, Priority
     * class, channel and secret, linked by {@code predecessor_ticket_id}. Its wait starts at {@code now}; the transfer Head start
     * is its Score adjustment (FR-QUE-053). It may be targeted at a Counter or an Agent (FR-QUE-003).
     */
    void insertSuccessor(UUID id, UUID predecessorId, UUID serviceId, UUID groupId, UUID zoneId, Instant now, int headStartMinutes, UUID targetCounterId, UUID targetAgentId) {
        jdbc.update(
                "INSERT INTO ticket (id, token_number, sequence_no, reset_key, service_id, service_group_id, site_id, zone_id, visit_id, predecessor_ticket_id,"
                        + " origin_channel, state, issued_at, queued_at, secret_hash, priority_class_id, score_adjustment_minutes, target_counter_id, target_agent_id)"
                        + " SELECT ?, token_number, sequence_no, reset_key, ?, ?, site_id, ?, visit_id, id, origin_channel, 'waiting', ?, ?, secret_hash,"
                        + " priority_class_id, ?, ?, ? FROM ticket WHERE id = ?",
                id, serviceId, groupId, zoneId, ts(now), ts(now), headStartMinutes, targetCounterId, targetAgentId, predecessorId);
    }

    // ---- mapping ----------------------------------------------------------------------------------------------

    private static CounterRow counter(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CounterRow(
                rs.getObject("id", UUID.class),
                rs.getString("label"),
                rs.getObject("zone_id", UUID.class),
                rs.getString("zone_name"),
                rs.getObject("site_id", UUID.class),
                rs.getBoolean("usable"));
    }

    private static SessionRow session(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SessionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("counter_id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                instant(rs, "opened_at"),
                instant(rs, "closed_at"),
                List.of((UUID[]) rs.getArray("services").getArray()),
                rs.getString("state"));
    }

    private BoundTicket bound(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BoundTicket(
                rs.getObject("id", UUID.class),
                rs.getString("token_number"),
                rs.getString("state"),
                rs.getInt("version"),
                rs.getObject("service_id", UUID.class),
                names(rs.getString("service_names")),
                rs.getString("origin_channel"),
                rs.getObject("class_id", UUID.class),
                rs.getString("class_names") == null ? Map.of() : names(rs.getString("class_names")),
                instant(rs, "queued_at"),
                instant(rs, "called_at"),
                instant(rs, "served_at"),
                rs.getInt("announce_count"),
                rs.getInt("miss_count"),
                rs.getInt("score_adjustment_minutes"),
                rs.getString("visitor_code"),
                rs.getString("visitor_name"),
                rs.getString("visitor_category"),
                rs.getString("purpose_note"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> names(String json) {
        return new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
