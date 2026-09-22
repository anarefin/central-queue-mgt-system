package com.qms.integration.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** The {@code webhook_event}, {@code webhook_delivery} and {@code webhook_delivery_attempt} tables (FR-INT-021):
 * from a just-observed, deduplicated business event, through one queued delivery per subscribed endpoint, to its
 * per-attempt log. */
@Repository
class WebhookDeliveryRepository {

    /** A due delivery joined with the event it carries and the endpoint it is sent to: everything
     * {@link WebhookDeliveryWorker} needs for one attempt. */
    record DueDelivery(
            UUID deliveryId, UUID endpointId, String endpointUrl, String eventType, Instant occurredAt, Map<String, Object> data, int attemptCount) {}

    record DeliveryRow(
            UUID id,
            @JsonProperty("event_id") UUID eventId,
            @JsonProperty("endpoint_id") UUID endpointId,
            @JsonProperty("event_type") String eventType,
            @JsonProperty("occurred_at") Instant occurredAt,
            Map<String, Object> data,
            String status,
            @JsonProperty("attempt_count") int attemptCount,
            @JsonProperty("last_error") String lastError,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("delivered_at") Instant deliveredAt) {}

    record AttemptRow(
            UUID id,
            @JsonProperty("delivery_id") UUID deliveryId,
            @JsonProperty("attempt_no") int attemptNo,
            boolean success,
            @JsonProperty("response_status") Integer responseStatus,
            String error,
            @JsonProperty("attempted_at") Instant attemptedAt) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    WebhookDeliveryRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Records the event unless its dedup key was already seen (a single business transition can fan out to several
     * realtime topics, e.g. queue.TicketEvents' queue:/ticket:/counter:/zone: for one ticket.called): the first
     * caller to see a given key wins and gets the event's id back; every later fan-out of the same event gets
     * nothing and queues no duplicate delivery. */
    Optional<UUID> recordEvent(String dedupKey, String type, Instant occurredAt, Map<String, Object> data, Instant now) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update(
                "INSERT INTO webhook_event (id, dedup_key, event_type, occurred_at, data, recorded_at) VALUES (?, ?, ?, ?, ?::jsonb, ?)"
                        + " ON CONFLICT (dedup_key) DO NOTHING",
                id, dedupKey, type, ts(occurredAt), json(data), ts(now));
        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    UUID queueDelivery(UUID eventId, UUID endpointId, String eventType, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO webhook_delivery (id, event_id, endpoint_id, event_type, status, attempt_count, next_attempt_at, created_at)"
                        + " VALUES (?, ?, ?, ?, 'queued', 0, ?, ?)",
                id, eventId, endpointId, eventType, ts(now), ts(now));
        return id;
    }

    /** Queued deliveries due now: never tried yet, or waiting on a retry (or a manual replay) whose
     * {@code next_attempt_at} has passed. */
    List<DueDelivery> due(Instant now, int limit) {
        return jdbc.query(
                "SELECT d.id AS delivery_id, d.endpoint_id, we.url AS endpoint_url, d.event_type, ev.occurred_at, ev.data, d.attempt_count"
                        + " FROM webhook_delivery d JOIN webhook_event ev ON ev.id = d.event_id JOIN webhook_endpoint we ON we.id = d.endpoint_id"
                        + " WHERE d.status = 'queued' AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= ?)"
                        + " ORDER BY d.created_at LIMIT ?",
                (rs, i) -> new DueDelivery(
                        rs.getObject("delivery_id", UUID.class),
                        rs.getObject("endpoint_id", UUID.class),
                        rs.getString("endpoint_url"),
                        rs.getString("event_type"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        readData(rs.getString("data")),
                        rs.getInt("attempt_count")),
                ts(now),
                limit);
    }

    void insertAttempt(UUID deliveryId, int attemptNo, boolean success, Integer responseStatus, String error, Instant now) {
        jdbc.update(
                "INSERT INTO webhook_delivery_attempt (id, delivery_id, attempt_no, success, response_status, error, attempted_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), deliveryId, attemptNo, success, responseStatus, error, ts(now));
    }

    void markSent(UUID id, Instant now) {
        jdbc.update("UPDATE webhook_delivery SET status = 'sent', delivered_at = ?, next_attempt_at = NULL, last_error = NULL WHERE id = ?", ts(now), id);
    }

    void retry(UUID id, int attemptCount, Instant nextAttemptAt, String error) {
        jdbc.update("UPDATE webhook_delivery SET attempt_count = ?, next_attempt_at = ?, last_error = ? WHERE id = ?", attemptCount, ts(nextAttemptAt), error, id);
    }

    void markFailed(UUID id, int attemptCount, String error) {
        jdbc.update("UPDATE webhook_delivery SET status = 'failed', attempt_count = ?, next_attempt_at = NULL, last_error = ? WHERE id = ?", attemptCount, error, id);
    }

    /** A manual replay from the delivery log (FR-INT-021): queued again for the very next sweep, regardless of its
     * current status. {@code attempt_count} is left as it stands, so an already-exhausted delivery gets exactly one
     * more try rather than resuming the full automatic retry schedule; the attempt log keeps counting up through it. */
    void resetForReplay(UUID id, Instant now) {
        jdbc.update("UPDATE webhook_delivery SET status = 'queued', next_attempt_at = ? WHERE id = ?", ts(now), id);
    }

    Optional<DeliveryRow> find(UUID id) {
        return jdbc.query(
                        "SELECT d.*, ev.occurred_at, ev.data FROM webhook_delivery d JOIN webhook_event ev ON ev.id = d.event_id WHERE d.id = ?",
                        this::map,
                        id)
                .stream()
                .findFirst();
    }

    /** The admin delivery log (FR-INT-021), filterable by endpoint, event type and status, newest first. */
    List<DeliveryRow> forAdmin(UUID endpointId, String eventType, String status, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT d.*, ev.occurred_at, ev.data FROM webhook_delivery d JOIN webhook_event ev ON ev.id = d.event_id WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (endpointId != null) {
            sql.append(" AND d.endpoint_id = ?");
            args.add(endpointId);
        }
        if (eventType != null) {
            sql.append(" AND d.event_type = ?");
            args.add(eventType);
        }
        if (status != null) {
            sql.append(" AND d.status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY d.created_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    List<AttemptRow> attemptsOf(UUID deliveryId) {
        return jdbc.query(
                "SELECT * FROM webhook_delivery_attempt WHERE delivery_id = ? ORDER BY attempt_no",
                (rs, i) -> new AttemptRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("delivery_id", UUID.class),
                        rs.getInt("attempt_no"),
                        rs.getBoolean("success"),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("error"),
                        rs.getObject("attempted_at", OffsetDateTime.class).toInstant()),
                deliveryId);
    }

    private DeliveryRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Instant deliveredAt = rs.getObject("delivered_at", OffsetDateTime.class) == null ? null : rs.getObject("delivered_at", OffsetDateTime.class).toInstant();
        return new DeliveryRow(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class),
                rs.getString("event_type"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                readData(rs.getString("data")),
                rs.getString("status"),
                rs.getInt("attempt_count"),
                rs.getString("last_error"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                deliveredAt);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readData(String json) {
        return json == null ? Map.of() : mapper.readValue(json, Map.class);
    }

    private String json(Map<String, Object> value) {
        return mapper.writeValueAsString(value);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
