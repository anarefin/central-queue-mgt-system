package com.qms.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class NotificationTemplateRepository {

    record TemplateRow(
            UUID id,
            @JsonProperty("trigger_key") String triggerKey,
            String channel,
            String language,
            String subject,
            String body,
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("updated_by") UUID updatedBy) {}

    private final JdbcTemplate jdbc;

    NotificationTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<TemplateRow> find(String triggerKey, String channel, String language) {
        return jdbc.query("SELECT * FROM notification_template WHERE trigger_key = ? AND channel = ? AND language = ?", this::map, triggerKey, channel, language)
                .stream()
                .findFirst();
    }

    List<TemplateRow> forTrigger(String triggerKey) {
        return jdbc.query("SELECT * FROM notification_template WHERE trigger_key = ? ORDER BY channel, language", this::map, triggerKey);
    }

    void upsert(String triggerKey, String channel, String language, String subject, String body, UUID actorId, Instant now) {
        int updated = jdbc.update(
                "UPDATE notification_template SET subject = ?, body = ?, updated_at = ?, updated_by = ? WHERE trigger_key = ? AND channel = ? AND language = ?",
                subject, body, ts(now), actorId, triggerKey, channel, language);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO notification_template (id, trigger_key, channel, language, subject, body, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), triggerKey, channel, language, subject, body, ts(now), actorId);
        }
    }

    private TemplateRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TemplateRow(
                rs.getObject("id", UUID.class), rs.getString("trigger_key"), rs.getString("channel"), rs.getString("language"),
                rs.getString("subject"), rs.getString("body"), rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_by", UUID.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
