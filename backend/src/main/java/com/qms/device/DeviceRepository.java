package com.qms.device;

import com.qms.platform.security.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** SQL for the {@code device} table. Nothing here deletes a row: revoking only flips {@code active}. */
@Repository
class DeviceRepository {

    private static final String COLUMNS = "id, kind, site_id, zone_id, label, active, paired_at, last_heartbeat_at, last_app_version,"
            + " layout, layout_config, language_cycle, language_cycle_seconds, next_n, highlight_seconds, columns, assignment_scope,"
            + " assignment_ids, created_at, updated_at";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    DeviceRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void insert(Device device) {
        jdbc.update(
                "INSERT INTO device (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?)",
                device.id(),
                device.kind().wire(),
                device.siteId(),
                device.zoneId(),
                device.label(),
                device.active(),
                ts(device.pairedAt()),
                ts(device.lastHeartbeatAt()),
                device.lastAppVersion(),
                device.layout(),
                mapper.writeValueAsString(device.layoutConfig()),
                mapper.writeValueAsString(device.languageCycle()),
                device.languageCycleSeconds(),
                device.nextN(),
                device.highlightSeconds(),
                mapper.writeValueAsString(device.columns()),
                device.assignmentScope(),
                mapper.writeValueAsString(ids(device.assignmentIds())),
                ts(device.createdAt()),
                ts(device.updatedAt()));
    }

    /** Ticket 28/30: the display-board configuration (layout and its config, language cycle and its interval, columns,
     * next-N, highlight period, assignment). */
    void updateDisplayConfig(Device device) {
        jdbc.update(
                "UPDATE device SET layout = ?, layout_config = ?::jsonb, language_cycle = ?::jsonb, language_cycle_seconds = ?,"
                        + " next_n = ?, highlight_seconds = ?, columns = ?::jsonb, assignment_scope = ?, assignment_ids = ?::jsonb,"
                        + " updated_at = ? WHERE id = ?",
                device.layout(),
                mapper.writeValueAsString(device.layoutConfig()),
                mapper.writeValueAsString(device.languageCycle()),
                device.languageCycleSeconds(),
                device.nextN(),
                device.highlightSeconds(),
                mapper.writeValueAsString(device.columns()),
                device.assignmentScope(),
                mapper.writeValueAsString(ids(device.assignmentIds())),
                ts(device.updatedAt()),
                device.id());
    }

    Optional<Device> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE id = ?", (rs, i) -> map(rs, mapper), id).stream().findFirst();
    }

    List<Device> all() {
        return jdbc.query("SELECT " + COLUMNS + " FROM device ORDER BY paired_at DESC, id", (rs, i) -> map(rs, mapper));
    }

    void setActive(UUID id, boolean active, Instant now) {
        jdbc.update("UPDATE device SET active = ?, updated_at = ? WHERE id = ?", active, ts(now), id);
    }

    void heartbeat(UUID id, Instant now, String appVersion) {
        jdbc.update(
                "UPDATE device SET last_heartbeat_at = ?, last_app_version = ?, updated_at = ? WHERE id = ?",
                ts(now), appVersion, ts(now), id);
    }

    /** Every active device of one Site, kiosk or display (FR-CFG-040's {@code config.changed} push). */
    List<UUID> activeIdsOfSite(UUID siteId) {
        return jdbc.queryForList("SELECT id FROM device WHERE site_id = ? AND active", UUID.class, siteId);
    }

    /** Every active device across every Site, for an organisation-wide change (a Priority class). */
    List<UUID> activeIds() {
        return jdbc.queryForList("SELECT id FROM device WHERE active", UUID.class);
    }

    /** Whether {@code counterId} is an existing counter of {@code zoneId} (FR-DSP-002's "counters" assignment). */
    boolean counterInZone(UUID counterId, UUID zoneId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM counter WHERE id = ? AND zone_id = ?", Integer.class, counterId, zoneId);
        return count != null && count > 0;
    }

    /** Whether {@code serviceId} is served by at least one counter of {@code zoneId} (FR-DSP-002's "queues" assignment). */
    boolean serviceServedInZone(UUID serviceId, UUID zoneId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM counter_service cs JOIN counter c ON c.id = cs.counter_id WHERE cs.service_id = ? AND c.zone_id = ?",
                Integer.class, serviceId, zoneId);
        return count != null && count > 0;
    }

    private static List<String> ids(List<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> strings(JsonMapper mapper, String json) {
        return json == null ? List.of() : List.copyOf(Arrays.asList(mapper.readValue(json, String[].class)));
    }

    private static List<UUID> uuids(JsonMapper mapper, String json) {
        return strings(mapper, json).stream().map(UUID::fromString).toList();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> object(JsonMapper mapper, String json) {
        return json == null ? java.util.Map.of() : java.util.Map.copyOf(mapper.readValue(json, java.util.LinkedHashMap.class));
    }

    private static Device map(ResultSet rs, JsonMapper mapper) throws SQLException {
        return new Device(
                rs.getObject("id", UUID.class),
                Role.fromWire(rs.getString("kind")),
                rs.getObject("site_id", UUID.class),
                rs.getObject("zone_id", UUID.class),
                rs.getString("label"),
                rs.getBoolean("active"),
                instant(rs, "paired_at"),
                instant(rs, "last_heartbeat_at"),
                rs.getString("last_app_version"),
                rs.getString("layout"),
                object(mapper, rs.getString("layout_config")),
                strings(mapper, rs.getString("language_cycle")),
                rs.getInt("language_cycle_seconds"),
                rs.getInt("next_n"),
                rs.getInt("highlight_seconds"),
                strings(mapper, rs.getString("columns")),
                rs.getString("assignment_scope"),
                uuids(mapper, rs.getString("assignment_ids")),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }
}
