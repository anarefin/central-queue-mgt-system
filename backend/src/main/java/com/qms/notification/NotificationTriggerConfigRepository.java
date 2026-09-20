package com.qms.notification;

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

@Repository
class NotificationTriggerConfigRepository {

    record SettingRow(UUID id, UUID siteId, UUID serviceId, String triggerKey, boolean enabled, List<String> channelOrder) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    NotificationTriggerConfigRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    Optional<SettingRow> siteSetting(UUID siteId, String triggerKey) {
        return jdbc.query("SELECT * FROM notification_trigger_setting WHERE site_id = ? AND trigger_key = ? AND service_id IS NULL", this::map, siteId, triggerKey)
                .stream()
                .findFirst();
    }

    Optional<SettingRow> serviceSetting(UUID siteId, UUID serviceId, String triggerKey) {
        return jdbc.query(
                        "SELECT * FROM notification_trigger_setting WHERE site_id = ? AND service_id = ? AND trigger_key = ?", this::map, siteId, serviceId, triggerKey)
                .stream()
                .findFirst();
    }

    List<SettingRow> forSite(UUID siteId) {
        return jdbc.query("SELECT * FROM notification_trigger_setting WHERE site_id = ? AND service_id IS NULL", this::map, siteId);
    }

    List<SettingRow> forService(UUID siteId, UUID serviceId) {
        return jdbc.query("SELECT * FROM notification_trigger_setting WHERE site_id = ? AND service_id = ?", this::map, siteId, serviceId);
    }

    void upsertSite(UUID siteId, String triggerKey, boolean enabled, List<String> channelOrder, UUID actorId, Instant now) {
        upsert(siteId, null, triggerKey, enabled, channelOrder, actorId, now);
    }

    void upsertService(UUID siteId, UUID serviceId, String triggerKey, boolean enabled, List<String> channelOrder, UUID actorId, Instant now) {
        upsert(siteId, serviceId, triggerKey, enabled, channelOrder, actorId, now);
    }

    private void upsert(UUID siteId, UUID serviceId, String triggerKey, boolean enabled, List<String> channelOrder, UUID actorId, Instant now) {
        String channelOrderJson = channelOrder == null ? null : json(channelOrder);
        int updated = serviceId == null
                ? jdbc.update(
                        "UPDATE notification_trigger_setting SET enabled = ?, channel_order = ?::jsonb, updated_at = ?, updated_by = ?"
                                + " WHERE site_id = ? AND trigger_key = ? AND service_id IS NULL",
                        enabled, channelOrderJson, ts(now), actorId, siteId, triggerKey)
                : jdbc.update(
                        "UPDATE notification_trigger_setting SET enabled = ?, channel_order = ?::jsonb, updated_at = ?, updated_by = ?"
                                + " WHERE site_id = ? AND service_id = ? AND trigger_key = ?",
                        enabled, channelOrderJson, ts(now), actorId, siteId, serviceId, triggerKey);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO notification_trigger_setting (id, site_id, service_id, trigger_key, enabled, channel_order, updated_at, updated_by)"
                            + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                    UUID.randomUUID(), siteId, serviceId, triggerKey, enabled, channelOrderJson, ts(now), actorId);
        }
    }

    private SettingRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SettingRow(
                rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class), rs.getObject("service_id", UUID.class),
                rs.getString("trigger_key"), rs.getBoolean("enabled"), readChannelOrder(rs.getString("channel_order")));
    }

    @SuppressWarnings("unchecked")
    private List<String> readChannelOrder(String json) {
        return json == null ? null : mapper.readValue(json, List.class);
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
