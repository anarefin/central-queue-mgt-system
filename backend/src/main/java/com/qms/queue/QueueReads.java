package com.qms.queue;

import com.qms.queue.QueueEngine.Candidate;
import com.qms.queue.QueueEngine.Scored;
import com.qms.queue.QueueEngine.Terms;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads of a service's queue at one site. A queue is logical (FR-QUE-001): it is the set of {@code waiting} and
 * {@code paused} tickets of a service, read through the partial index on (service, state); a ticket never moves
 * between tables, only its state changes. The order comes from {@link QueueEngine} under the strategy of the service's
 * group (FR-QUE-021), computed on read from each ticket's class and the clock, so nothing about the order is stored.
 */
@Repository
public class QueueReads {

    /** A ticket in a queue with its 1-based place, its Priority class and the terms of its score. */
    public record Entry(
            UUID ticketId,
            String tokenNumber,
            String state,
            String originChannel,
            Instant queuedAt,
            int position,
            UUID priorityClassId,
            Map<String, String> priorityClassNames,
            Integer maxWaitMinutes,
            Terms terms,
            TransferRules.Target target) {

        /** Past its class's maximum wait, so it is served before every ticket that is not (FR-QUE-022). */
        public boolean escalated() {
            return terms.escalated();
        }
    }

    /** A queue in order under one strategy at one moment. */
    public record Ordered(QueueStrategy strategy, Instant computedAt, List<Entry> entries) {}

    private static final String WAITING = "state IN ('waiting', 'paused')";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final Clock clock;

    QueueReads(JdbcTemplate jdbc, JsonMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public int waitingCount(UUID serviceId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM ticket WHERE service_id = ? AND " + WAITING, Integer.class, serviceId);
        return count == null ? 0 : count;
    }

    /** The strategy of the service's group: the one chosen for it, else {@code weighted_wait}. */
    public QueueStrategy strategyOf(UUID serviceId) {
        return jdbc.query(
                        "SELECT rs.strategy FROM service v JOIN routing_strategy rs ON rs.service_group_id = v.service_group_id WHERE v.id = ?",
                        (rs, i) -> QueueStrategy.fromWire(rs.getString("strategy")).orElse(QueueStrategy.DEFAULT),
                        serviceId)
                .stream().findFirst().orElse(QueueStrategy.DEFAULT);
    }

    /** The whole queue in order under the group's strategy, or under {@code strategy} when given (the dry-run). */
    public Ordered ordered(UUID serviceId, QueueStrategy strategy) {
        QueueStrategy used = strategy == null ? strategyOf(serviceId) : strategy;
        Instant now = clock.instant();
        List<Row> rows = jdbc.query(
                "SELECT t.id, t.token_number, t.state, t.origin_channel, t.issued_at, t.queued_at, t.score_adjustment_minutes, t.appointment_bonus_minutes,"
                        + " t.target_counter_id, t.target_agent_id, pc.id AS class_id, pc.name_i18n AS class_names, pc.headstart_minutes, pc.max_wait_minutes"
                        + " FROM ticket t LEFT JOIN priority_class pc ON pc.id = coalesce(t.priority_class_id, (SELECT id FROM priority_class WHERE is_default))"
                        + " WHERE t.service_id = ? AND t." + WAITING,
                (rs, i) -> {
                    Integer maxWait = rs.getObject("max_wait_minutes", Integer.class);
                    Candidate candidate = new Candidate(
                            rs.getObject("id", UUID.class),
                            rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("queued_at", OffsetDateTime.class).toInstant(),
                            rs.getInt("headstart_minutes"),
                            maxWait,
                            rs.getInt("appointment_bonus_minutes"), // a checked-in appointment's fixed bonus, read once at issue (FR-QUE-020, FR-APT-032)
                            rs.getInt("score_adjustment_minutes"));
                    return new Row(candidate, rs.getString("token_number"), rs.getString("state"), rs.getString("origin_channel"),
                            rs.getObject("class_id", UUID.class), names(rs.getString("class_names")),
                            new TransferRules.Target(rs.getObject("target_counter_id", UUID.class), rs.getObject("target_agent_id", UUID.class)));
                },
                serviceId);
        Map<UUID, Row> byId = new LinkedHashMap<>();
        for (Row row : rows) byId.put(row.candidate().id(), row);
        List<Scored> order = QueueEngine.order(rows.stream().map(Row::candidate).toList(), used, now);
        List<Entry> entries = order.stream().map(s -> entry(byId.get(s.ticket().id()), s)).toList();
        return new Ordered(used, now, entries);
    }

    /**
     * The ticket a counter would be given next from this service: the first in order that is {@code waiting} and that this
     * counter and agent may draw. A {@code paused} ticket keeps its place in the queue but cannot be called (§19.1); one
     * targeted at another agent or counter waits in that agent's personal queue and is not drawn here (FR-QUE-003).
     */
    public Optional<Entry> callableHead(UUID serviceId, UUID counterId, UUID agentId) {
        return ordered(serviceId, null).entries().stream()
                .filter(e -> "waiting".equals(e.state()) && e.target().drawableBy(counterId, agentId))
                .findFirst();
    }

    /**
     * The Score adjustment that returns a missed ticket to the queue of its Service at {@code position} (FR-QUE-051,
     * ADR-0004): its own terms without any adjustment, set against the tickets waiting now. The ticket itself is left out
     * of the others, so this reads the same before and after it returns to {@code waiting}.
     */
    public int reentryAdjustment(UUID ticketId, ReentryPosition position, int after) {
        record Own(UUID serviceId, Candidate candidate) {}
        Own own = jdbc.query(
                        "SELECT t.service_id, t.issued_at, t.queued_at, t.appointment_bonus_minutes, pc.headstart_minutes, pc.max_wait_minutes"
                                + " FROM ticket t LEFT JOIN priority_class pc ON pc.id = coalesce(t.priority_class_id, (SELECT id FROM priority_class WHERE is_default))"
                                + " WHERE t.id = ?",
                        (rs, i) -> new Own(
                                rs.getObject("service_id", UUID.class),
                                new Candidate(
                                        ticketId,
                                        rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                                        rs.getObject("queued_at", OffsetDateTime.class).toInstant(),
                                        rs.getInt("headstart_minutes"),
                                        rs.getObject("max_wait_minutes", Integer.class),
                                        rs.getInt("appointment_bonus_minutes"),
                                        0)),
                        ticketId)
                .stream().findFirst().orElseThrow();
        List<Terms> others = ordered(own.serviceId(), null).entries().stream().filter(e -> !e.ticketId().equals(ticketId)).map(Entry::terms).toList();
        return QueueEngine.reentryAdjustment(QueueEngine.terms(own.candidate(), clock.instant()), others, position, after);
    }

    /** The place of a queued ticket, or null once it has left the queue. */
    public Integer positionOf(UUID ticketId) {
        List<UUID> service = jdbc.query("SELECT service_id FROM ticket WHERE id = ? AND " + WAITING, (rs, i) -> rs.getObject("service_id", UUID.class), ticketId);
        if (service.isEmpty()) return null;
        return ordered(service.getFirst(), null).entries().stream()
                .filter(e -> e.ticketId().equals(ticketId))
                .map(Entry::position)
                .findFirst()
                .orElse(null);
    }

    /** The first {@code limit} tickets of the queue, in order. */
    public List<Entry> next(UUID serviceId, int limit) {
        List<Entry> entries = ordered(serviceId, null).entries();
        return entries.size() <= limit ? entries : entries.subList(0, limit);
    }

    private record Row(Candidate candidate, String tokenNumber, String state, String originChannel, UUID classId, Map<String, String> classNames, TransferRules.Target target) {}

    private static Entry entry(Row row, Scored scored) {
        return new Entry(
                row.candidate().id(),
                row.tokenNumber(),
                row.state(),
                row.originChannel(),
                row.candidate().waitingSince(),
                scored.position(),
                row.classId(),
                row.classNames(),
                row.candidate().maxWaitMinutes(),
                scored.terms(),
                row.target());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> names(String json) {
        return json == null ? Map.of() : new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }
}
