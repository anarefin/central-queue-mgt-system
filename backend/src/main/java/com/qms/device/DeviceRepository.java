package com.qms.device;

import com.qms.platform.security.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL for the {@code device} table. Nothing here deletes a row: revoking only flips {@code active}. */
@Repository
class DeviceRepository {

    private static final String COLUMNS =
            "id, kind, site_id, zone_id, label, active, paired_at, last_heartbeat_at, last_app_version, created_at, updated_at";

    private final JdbcTemplate jdbc;

    DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Device device) {
        jdbc.update(
                "INSERT INTO device (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                device.id(),
                device.kind().wire(),
                device.siteId(),
                device.zoneId(),
                device.label(),
                device.active(),
                ts(device.pairedAt()),
                ts(device.lastHeartbeatAt()),
                device.lastAppVersion(),
                ts(device.createdAt()),
                ts(device.updatedAt()));
    }

    Optional<Device> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE id = ?", (rs, i) -> map(rs), id).stream().findFirst();
    }

    List<Device> all() {
        return jdbc.query("SELECT " + COLUMNS + " FROM device ORDER BY paired_at DESC, id", (rs, i) -> map(rs));
    }

    void setActive(UUID id, boolean active, Instant now) {
        jdbc.update("UPDATE device SET active = ?, updated_at = ? WHERE id = ?", active, ts(now), id);
    }

    void heartbeat(UUID id, Instant now, String appVersion) {
        jdbc.update(
                "UPDATE device SET last_heartbeat_at = ?, last_app_version = ?, updated_at = ? WHERE id = ?",
                ts(now), appVersion, ts(now), id);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Device map(ResultSet rs) throws SQLException {
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
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }
}
