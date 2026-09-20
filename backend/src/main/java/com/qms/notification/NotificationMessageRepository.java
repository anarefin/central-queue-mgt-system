package com.qms.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The notification_message table (ticket 38): one row per message from the moment a trigger queues it (FR-NTF-003)
 * to its final status, plus the per-attempt delivery log rows a message accumulates (FR-NTF-032).
 */
@Repository
class NotificationMessageRepository {

    record MessageRow(
            UUID id,
            @JsonProperty("trigger_key") String triggerKey,
            String channel,
            @JsonProperty("channel_order") List<String> channelOrder,
            @JsonProperty("channel_index") int channelIndex,
            String language,
            boolean urgent,
            @JsonProperty("site_id") UUID siteId,
            @JsonProperty("service_id") UUID serviceId,
            @JsonProperty("ticket_id") UUID ticketId,
            @JsonProperty("visitor_id") UUID visitorId,
            Map<String, String> variables,
            @JsonProperty("rendered_subject") String renderedSubject,
            @JsonProperty("rendered_body") String renderedBody,
            String status,
            @JsonProperty("attempt_count") int attemptCount,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("sent_at") Instant sentAt) {}

    record AttemptRow(
            UUID id,
            @JsonProperty("message_id") UUID messageId,
            @JsonProperty("attempt_no") int attemptNo,
            String channel,
            String status,
            @JsonProperty("provider_response") String providerResponse,
            @JsonProperty("attempted_at") Instant attemptedAt) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    NotificationMessageRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    UUID insertQueued(
            String triggerKey,
            List<String> channelOrder,
            String language,
            boolean urgent,
            UUID siteId,
            UUID serviceId,
            UUID ticketId,
            UUID visitorId,
            Map<String, String> variables,
            String renderedSubject,
            String renderedBody,
            Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notification_message (id, trigger_key, channel, channel_order, channel_index, language, urgent, site_id,"
                        + " service_id, ticket_id, visitor_id, variables, rendered_subject, rendered_body, status, attempt_count, next_attempt_at, created_at)"
                        + " VALUES (?, ?, ?, ?::jsonb, 0, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'queued', 0, ?, ?)",
                id, triggerKey, channelOrder.get(0), json(channelOrder), language, urgent, siteId, serviceId, ticketId, visitorId,
                json(variables), renderedSubject, renderedBody, ts(now), ts(now));
        return id;
    }

    void insertSuppressed(
            String triggerKey,
            List<String> channelOrder,
            String language,
            boolean urgent,
            UUID siteId,
            UUID serviceId,
            UUID ticketId,
            UUID visitorId,
            Map<String, String> variables,
            String renderedSubject,
            String renderedBody,
            String reason,
            Instant now) {
        jdbc.update(
                "INSERT INTO notification_message (id, trigger_key, channel, channel_order, channel_index, language, urgent, site_id,"
                        + " service_id, ticket_id, visitor_id, variables, rendered_subject, rendered_body, status, attempt_count, suppressed_reason, created_at)"
                        + " VALUES (?, ?, ?, ?::jsonb, 0, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'suppressed', 0, ?, ?)",
                UUID.randomUUID(), triggerKey, channelOrder.isEmpty() ? "none" : channelOrder.get(0), json(channelOrder), language, urgent,
                siteId, serviceId, ticketId, visitorId, json(variables), renderedSubject, renderedBody, reason, ts(now));
    }

    /** Queued messages due now: never tried yet, or waiting on a retry whose {@code next_attempt_at} has passed. */
    List<MessageRow> due(Instant now, int limit) {
        return jdbc.query(
                "SELECT * FROM notification_message WHERE status = 'queued' AND (next_attempt_at IS NULL OR next_attempt_at <= ?)"
                        + " ORDER BY created_at LIMIT ?",
                this::map, ts(now), limit);
    }

    /** How many attempts this message has made so far, on any channel: the next one's {@code attempt_no} (FR-NTF-032). */
    int attemptCountAcrossChannels(UUID messageId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM notification_delivery_attempt WHERE message_id = ?", Integer.class, messageId);
        return count == null ? 0 : count;
    }

    void insertAttempt(UUID messageId, int attemptNo, String channel, boolean success, String providerResponse, Instant now) {
        jdbc.update(
                "INSERT INTO notification_delivery_attempt (id, message_id, attempt_no, channel, status, provider_response, attempted_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), messageId, attemptNo, channel, success ? "sent" : "failed", providerResponse, ts(now));
    }

    void markSent(UUID id, Instant now) {
        jdbc.update("UPDATE notification_message SET status = 'sent', sent_at = ?, next_attempt_at = NULL WHERE id = ?", ts(now), id);
    }

    /** Another attempt is due on the same channel, after the backoff (FR-NTF-033). */
    void retrySameChannel(UUID id, int attemptCount, Instant nextAttemptAt) {
        jdbc.update("UPDATE notification_message SET attempt_count = ?, next_attempt_at = ? WHERE id = ?", attemptCount, ts(nextAttemptAt), id);
    }

    /** The channel just exhausted its retries; fall to the next one in the order, re-rendered for it, tried at once. */
    void switchChannel(UUID id, int channelIndex, String channel, String renderedSubject, String renderedBody, Instant now) {
        jdbc.update(
                "UPDATE notification_message SET channel_index = ?, channel = ?, rendered_subject = ?, rendered_body = ?, attempt_count = 0, next_attempt_at = ? WHERE id = ?",
                channelIndex, channel, renderedSubject, renderedBody, ts(now), id);
    }

    /** Every channel in the order is exhausted: terminal (FR-NTF-033 ends here; nothing retries a {@code failed} message). */
    void markFailed(UUID id) {
        jdbc.update("UPDATE notification_message SET status = 'failed', next_attempt_at = NULL WHERE id = ?", id);
    }

    /** How many non-suppressed messages this ticket has already had queued or sent (FR-NTF-030). */
    int countForTicket(UUID ticketId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM notification_message WHERE ticket_id = ? AND status <> 'suppressed'", Integer.class, ticketId);
        return count == null ? 0 : count;
    }

    /** How many non-suppressed messages this visitor has already had today, in the Site's own local day (FR-NTF-030). */
    int countForVisitorToday(UUID visitorId, ZoneId zone, Instant now) {
        Instant startOfDay = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        Instant startOfNextDay = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM notification_message WHERE visitor_id = ? AND status <> 'suppressed' AND created_at >= ? AND created_at < ?",
                Integer.class, visitorId, ts(startOfDay), ts(startOfNextDay));
        return count == null ? 0 : count;
    }

    /** The admin delivery log (FR-NTF-032), filterable by ticket, visitor and status, newest first. */
    List<MessageRow> forAdmin(UUID ticketId, UUID visitorId, String status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM notification_message WHERE 1 = 1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (ticketId != null) {
            sql.append(" AND ticket_id = ?");
            args.add(ticketId);
        }
        if (visitorId != null) {
            sql.append(" AND visitor_id = ?");
            args.add(visitorId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    List<AttemptRow> attemptsOf(UUID messageId) {
        return jdbc.query(
                "SELECT * FROM notification_delivery_attempt WHERE message_id = ? ORDER BY attempt_no",
                (rs, i) -> new AttemptRow(
                        rs.getObject("id", UUID.class), rs.getObject("message_id", UUID.class), rs.getInt("attempt_no"), rs.getString("channel"),
                        rs.getString("status"), rs.getString("provider_response"), rs.getObject("attempted_at", OffsetDateTime.class).toInstant()),
                messageId);
    }

    private MessageRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Instant sentAt = rs.getObject("sent_at", OffsetDateTime.class) == null ? null : rs.getObject("sent_at", OffsetDateTime.class).toInstant();
        return new MessageRow(
                rs.getObject("id", UUID.class),
                rs.getString("trigger_key"),
                rs.getString("channel"),
                readChannelOrder(rs.getString("channel_order")),
                rs.getInt("channel_index"),
                rs.getString("language"),
                rs.getBoolean("urgent"),
                rs.getObject("site_id", UUID.class),
                rs.getObject("service_id", UUID.class),
                rs.getObject("ticket_id", UUID.class),
                rs.getObject("visitor_id", UUID.class),
                readVariables(rs.getString("variables")),
                rs.getString("rendered_subject"),
                rs.getString("rendered_body"),
                rs.getString("status"),
                rs.getInt("attempt_count"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                sentAt);
    }

    @SuppressWarnings("unchecked")
    private List<String> readChannelOrder(String json) {
        return json == null ? List.of() : mapper.readValue(json, List.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readVariables(String json) {
        return json == null ? Map.of() : mapper.readValue(json, Map.class);
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
