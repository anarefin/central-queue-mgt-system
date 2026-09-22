package com.qms.integration.webhook;

import com.qms.configuration.privacy.PiiCipher;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** The {@code webhook_endpoint} table (FR-INT-020): admin-configured subscriptions, one row per endpoint, its secret
 * encrypted at rest with the same application-layer cipher {@code configuration.privacy} already uses for other
 * sensitive columns. */
@Repository
class WebhookEndpointRepository {

    record Row(UUID id, String description, String url, List<String> eventTypes, boolean active, Instant createdAt, Instant updatedAt) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final PiiCipher cipher;

    WebhookEndpointRepository(JdbcTemplate jdbc, JsonMapper mapper, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.cipher = cipher;
    }

    UUID insert(String description, String url, List<String> eventTypes, String secret, UUID createdBy, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO webhook_endpoint (id, description, url, secret, event_types, active, created_by, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb, true, ?, ?, ?)",
                id, description, url, cipher.encrypt(secret), json(eventTypes), createdBy, ts(now), ts(now));
        return id;
    }

    void update(UUID id, String description, String url, List<String> eventTypes, Instant now) {
        jdbc.update(
                "UPDATE webhook_endpoint SET description = ?, url = ?, event_types = ?::jsonb, updated_at = ? WHERE id = ?",
                description, url, json(eventTypes), ts(now), id);
    }

    void rotateSecret(UUID id, String secret, Instant now) {
        jdbc.update("UPDATE webhook_endpoint SET secret = ?, updated_at = ? WHERE id = ?", cipher.encrypt(secret), ts(now), id);
    }

    void setActive(UUID id, boolean active, Instant now) {
        jdbc.update("UPDATE webhook_endpoint SET active = ?, updated_at = ? WHERE id = ?", active, ts(now), id);
    }

    Optional<Row> find(UUID id) {
        return jdbc.query("SELECT * FROM webhook_endpoint WHERE id = ?", this::map, id).stream().findFirst();
    }

    List<Row> all() {
        return jdbc.query("SELECT * FROM webhook_endpoint ORDER BY created_at", this::map);
    }

    /** The decrypted secret this endpoint's delivery is signed with; only {@link WebhookDeliveryWorker} calls this. */
    Optional<String> secretOf(UUID id) {
        return jdbc.query("SELECT secret FROM webhook_endpoint WHERE id = ?", (rs, i) -> cipher.decrypt(rs.getString("secret")), id).stream().findFirst();
    }

    /** Every active endpoint subscribed to this event type (FR-INT-020): the fan-out list a newly recorded event
     * queues a delivery for. The {@code @>} jsonb containment operator matches an array holding this one element. */
    List<UUID> activeSubscribers(String eventType) {
        return jdbc.query(
                "SELECT id FROM webhook_endpoint WHERE active = true AND event_types @> ?::jsonb",
                (rs, i) -> rs.getObject("id", UUID.class),
                mapper.writeValueAsString(List.of(eventType)));
    }

    private Row map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("description"),
                rs.getString("url"),
                readEventTypes(rs.getString("event_types")),
                rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    @SuppressWarnings("unchecked")
    private List<String> readEventTypes(String json) {
        return json == null ? List.of() : mapper.readValue(json, List.class);
    }

    private String json(List<String> value) {
        return mapper.writeValueAsString(value);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
