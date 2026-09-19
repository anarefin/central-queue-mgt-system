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
            Instant servedAt) {}

    record OutcomeRow(UUID id, String code, Map<String, String> labels) {}

    private static final String BOUND =
            "SELECT t.id, t.token_number, t.state, t.version, t.service_id, v.name_i18n AS service_names, t.origin_channel,"
                    + " pc.id AS class_id, pc.name_i18n AS class_names, t.queued_at, t.called_at, t.served_at"
                    + " FROM ticket t JOIN service v ON v.id = t.service_id"
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

    // ---- the tickets a session drives -------------------------------------------------------------------------

    /** The tickets bound to the session that are not finished: called, serving or held (ADR-0008). */
    List<BoundTicket> unresolved(UUID sessionId) {
        return jdbc.query(
                BOUND + " WHERE t.counter_session_id = ? AND t.state IN ('called', 'serving', 'held') ORDER BY t.called_at, t.id",
                (rs, i) -> bound(rs),
                sessionId);
    }

    /** Binds a waiting ticket to a session under optimistic concurrency on its version; false when someone else got there first. */
    boolean call(UUID ticketId, int version, UUID sessionId, UUID counterId, UUID agentId, Instant now) {
        return jdbc.update(
                "UPDATE ticket SET state = ?, counter_session_id = ?, counter_id = ?, agent_id = ?, called_at = ?, version = version + 1"
                        + " WHERE id = ? AND version = ? AND state = ? AND counter_session_id IS NULL",
                TicketTransition.CALL.to(), sessionId, counterId, agentId, ts(now), ticketId, version, TicketTransition.CALL.from()) == 1;
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
                instant(rs, "served_at"));
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
