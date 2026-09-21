package com.qms.reporting;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@code reporting.report_schedule} and {@code reporting.report_schedule_delivery} (ticket 52, FR-RPT-005). */
@Repository
class ReportScheduleRepository {

    record Row(
            UUID id,
            String reportKey,
            String cadence,
            String format,
            List<String> recipients,
            String filterJson,
            Set<UUID> allowedSites,
            Set<UUID> allowedGroups,
            List<String> creatorRoles,
            boolean enabled,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            Instant nextRunAt,
            Instant lastRunAt) {}

    record DeliveryRow(UUID id, UUID scheduleId, Instant runAt, String recipient, String status, Long rowCount, String error, Instant attemptedAt) {}

    private static final String COLUMNS = "id, report_key, cadence, format, recipients, filter::text AS filter, allowed_sites, allowed_groups,"
            + " creator_roles, enabled, created_by, created_at, updated_at, next_run_at, last_run_at";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    ReportScheduleRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    UUID insert(
            String reportKey, String cadence, String format, List<String> recipients, ReportScheduleFilter filter, Set<UUID> allowedSites,
            Set<UUID> allowedGroups, List<String> creatorRoles, UUID createdBy, Instant now, Instant nextRunAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO reporting.report_schedule"
                            + " (id, report_key, cadence, format, recipients, filter, allowed_sites, allowed_groups, creator_roles, enabled,"
                            + " created_by, created_at, updated_at, next_run_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, true, ?, ?, ?, ?)");
            ps.setObject(1, id);
            ps.setString(2, reportKey);
            ps.setString(3, cadence);
            ps.setString(4, format);
            ps.setArray(5, textArray(connection, recipients));
            ps.setString(6, mapper.writeValueAsString(filter));
            ps.setArray(7, uuidArray(connection, allowedSites));
            ps.setArray(8, uuidArray(connection, allowedGroups));
            ps.setArray(9, textArray(connection, creatorRoles));
            ps.setObject(10, createdBy);
            ps.setObject(11, ts(now));
            ps.setObject(12, ts(now));
            ps.setObject(13, ts(nextRunAt));
            return ps;
        });
        return id;
    }

    /** Every schedule, most recently created first — shared admin configuration, not a per-user list, the same
     * visibility {@code notice_board:manage} content already has. */
    List<Row> list() {
        return jdbc.query("SELECT " + COLUMNS + " FROM reporting.report_schedule ORDER BY created_at DESC", (rs, i) -> map(rs));
    }

    Optional<Row> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM reporting.report_schedule WHERE id = ?", (rs, i) -> map(rs), id).stream().findFirst();
    }

    /** Due, soonest first, so a persistently late schedule is never starved behind newer ones. */
    List<Row> due(Instant now, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM reporting.report_schedule WHERE enabled = true AND next_run_at <= ? ORDER BY next_run_at ASC LIMIT ?",
                (rs, i) -> map(rs), ts(now), limit);
    }

    void update(UUID id, String cadence, String format, List<String> recipients, ReportScheduleFilter filter, boolean enabled, Instant now, Instant nextRunAt) {
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "UPDATE reporting.report_schedule"
                            + " SET cadence = ?, format = ?, recipients = ?, filter = ?::jsonb, enabled = ?, updated_at = ?, next_run_at = ?"
                            + " WHERE id = ?");
            ps.setString(1, cadence);
            ps.setString(2, format);
            ps.setArray(3, textArray(connection, recipients));
            ps.setString(4, mapper.writeValueAsString(filter));
            ps.setBoolean(5, enabled);
            ps.setObject(6, ts(now));
            ps.setObject(7, ts(nextRunAt));
            ps.setObject(8, id);
            return ps;
        });
    }

    void delete(UUID id) {
        jdbc.update("DELETE FROM reporting.report_schedule_delivery WHERE schedule_id = ?", id);
        jdbc.update("DELETE FROM reporting.report_schedule WHERE id = ?", id);
    }

    /** Advances a schedule past this tick, whether its delivery succeeded or failed — a persistently broken
     * schedule retries at its own cadence, not every sweep. */
    void markRun(UUID id, Instant ranAt, Instant nextRunAt) {
        jdbc.update("UPDATE reporting.report_schedule SET last_run_at = ?, next_run_at = ? WHERE id = ?", ts(ranAt), ts(nextRunAt), id);
    }

    void insertDelivery(UUID scheduleId, Instant runAt, String recipient, String status, Long rowCount, String error, Instant attemptedAt) {
        jdbc.update(
                "INSERT INTO reporting.report_schedule_delivery (id, schedule_id, run_at, recipient, status, row_count, error, attempted_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), scheduleId, ts(runAt), recipient, status, rowCount, truncate(error), ts(attemptedAt));
    }

    List<DeliveryRow> deliveries(UUID scheduleId, int limit) {
        return jdbc.query(
                "SELECT id, schedule_id, run_at, recipient, status, row_count, error, attempted_at FROM reporting.report_schedule_delivery"
                        + " WHERE schedule_id = ? ORDER BY attempted_at DESC LIMIT ?",
                (rs, i) -> mapDelivery(rs), scheduleId, limit);
    }

    private Row map(ResultSet rs) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("report_key"),
                rs.getString("cadence"),
                rs.getString("format"),
                textList(rs.getArray("recipients")),
                rs.getString("filter"),
                uuidSet(rs.getArray("allowed_sites")),
                uuidSet(rs.getArray("allowed_groups")),
                textList(rs.getArray("creator_roles")),
                rs.getBoolean("enabled"),
                rs.getObject("created_by", UUID.class),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class)),
                instant(rs.getObject("next_run_at", OffsetDateTime.class)),
                instant(rs.getObject("last_run_at", OffsetDateTime.class)));
    }

    private DeliveryRow mapDelivery(ResultSet rs) throws SQLException {
        return new DeliveryRow(
                rs.getObject("id", UUID.class),
                rs.getObject("schedule_id", UUID.class),
                instant(rs.getObject("run_at", OffsetDateTime.class)),
                rs.getString("recipient"),
                rs.getString("status"),
                (Long) rs.getObject("row_count"),
                rs.getString("error"),
                instant(rs.getObject("attempted_at", OffsetDateTime.class)));
    }

    private static Array uuidArray(java.sql.Connection connection, Set<UUID> ids) throws SQLException {
        return ids == null ? null : connection.createArrayOf("uuid", ids.toArray(UUID[]::new));
    }

    private static Array textArray(java.sql.Connection connection, List<String> values) throws SQLException {
        return connection.createArrayOf("text", values == null ? new String[0] : values.toArray(String[]::new));
    }

    private static Set<UUID> uuidSet(Array array) throws SQLException {
        if (array == null) return null;
        Object[] raw = (Object[]) array.getArray();
        List<UUID> ids = new ArrayList<>(raw.length);
        for (Object value : raw) ids.add((UUID) value);
        return Set.copyOf(ids);
    }

    private static List<String> textList(Array array) throws SQLException {
        if (array == null) return List.of();
        Object[] raw = (Object[]) array.getArray();
        List<String> values = new ArrayList<>(raw.length);
        for (Object value : raw) values.add((String) value);
        return List.copyOf(values);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    private static String truncate(String value) {
        return value != null && value.length() > 2000 ? value.substring(0, 2000) : value;
    }
}
