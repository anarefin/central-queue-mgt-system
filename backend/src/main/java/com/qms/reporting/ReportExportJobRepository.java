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

/** {@code reporting.export_job} (ticket 49, FR-RPT-004): a background export's own record, from {@code queued}
 * through {@code running} to {@code done} or {@code failed}. */
@Repository
class ReportExportJobRepository {

    record Row(
            UUID id,
            String reportKey,
            String format,
            String filterJson,
            Set<UUID> allowedSites,
            Set<UUID> allowedGroups,
            boolean containsPii,
            UUID requestedBy,
            String status,
            Long rowCount,
            String filePath,
            String error,
            Instant requestedAt,
            Instant completedAt,
            Instant expiresAt) {}

    private static final String COLUMNS =
            "id, report_key, format, filter::text AS filter, allowed_sites, allowed_groups, contains_pii, requested_by, status, row_count,"
                    + " file_path, error, requested_at, completed_at, expires_at";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    ReportExportJobRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void insertQueued(
            UUID id, String reportKey, ReportExportFormat format, DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups,
            boolean containsPii, UUID requestedBy, Instant requestedAt) {
        jdbc.update(
                connection -> {
                    var ps = connection.prepareStatement(
                            "INSERT INTO reporting.export_job"
                                    + " (id, report_key, format, filter, allowed_sites, allowed_groups, contains_pii, requested_by, status, requested_at)"
                                    + " VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, 'queued', ?)");
                    ps.setObject(1, id);
                    ps.setString(2, reportKey);
                    ps.setString(3, format.wire());
                    ps.setString(4, mapper.writeValueAsString(filter));
                    ps.setArray(5, uuidArray(connection, allowedSites));
                    ps.setArray(6, uuidArray(connection, allowedGroups));
                    ps.setBoolean(7, containsPii);
                    ps.setObject(8, requestedBy);
                    ps.setObject(9, ts(requestedAt));
                    return ps;
                });
    }

    /** Oldest-first, so a queued job is never starved behind a stream of newer ones. */
    List<Row> due(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM reporting.export_job WHERE status = 'queued' ORDER BY requested_at ASC LIMIT ?", (rs, i) -> map(rs), limit);
    }

    Optional<Row> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM reporting.export_job WHERE id = ?", (rs, i) -> map(rs), id).stream().findFirst();
    }

    void markRunning(UUID id, Instant startedAt) {
        jdbc.update("UPDATE reporting.export_job SET status = 'running', started_at = ? WHERE id = ?", ts(startedAt), id);
    }

    void markDone(UUID id, String filePath, long rowCount, Instant completedAt, Instant expiresAt) {
        jdbc.update(
                "UPDATE reporting.export_job SET status = 'done', file_path = ?, row_count = ?, completed_at = ?, expires_at = ? WHERE id = ?",
                filePath, rowCount, ts(completedAt), ts(expiresAt), id);
    }

    void markFailed(UUID id, String error, Instant completedAt) {
        jdbc.update("UPDATE reporting.export_job SET status = 'failed', error = ?, completed_at = ? WHERE id = ?", truncate(error), ts(completedAt), id);
    }

    private Row map(ResultSet rs) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("report_key"),
                rs.getString("format"),
                rs.getString("filter"),
                uuidSet(rs.getArray("allowed_sites")),
                uuidSet(rs.getArray("allowed_groups")),
                rs.getBoolean("contains_pii"),
                rs.getObject("requested_by", UUID.class),
                rs.getString("status"),
                (Long) rs.getObject("row_count"),
                rs.getString("file_path"),
                rs.getString("error"),
                instant(rs.getObject("requested_at", OffsetDateTime.class)),
                instant(rs.getObject("completed_at", OffsetDateTime.class)),
                instant(rs.getObject("expires_at", OffsetDateTime.class)));
    }

    private static Array uuidArray(java.sql.Connection connection, Set<UUID> ids) throws SQLException {
        return ids == null ? null : connection.createArrayOf("uuid", ids.toArray(UUID[]::new));
    }

    private static Set<UUID> uuidSet(Array array) throws SQLException {
        if (array == null) return null;
        Object[] raw = (Object[]) array.getArray();
        List<UUID> ids = new ArrayList<>(raw.length);
        for (Object value : raw) ids.add((UUID) value);
        return Set.copyOf(ids);
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
