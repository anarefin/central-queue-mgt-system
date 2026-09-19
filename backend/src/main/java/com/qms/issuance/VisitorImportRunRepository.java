package com.qms.issuance;

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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Persisted FR-INT-011 validation reports, one row per CSV file processed, manual or scheduled. There is no update or delete: a run is a fact. */
@Repository
class VisitorImportRunRepository {

    private static final String COLUMNS = "id, source, filename, started_at, completed_at, status, total_rows, inserted_count, updated_count, failed_count, errors::text AS errors";
    private static final TypeReference<List<VisitorImportError>> ERROR_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    VisitorImportRunRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void insert(VisitorImportReport report, UUID triggeredBy) {
        jdbc.update(
                "INSERT INTO visitor_import_run (id, source, filename, triggered_by, started_at, completed_at, status, total_rows, inserted_count, updated_count, failed_count, errors) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                report.id(),
                report.source(),
                report.filename(),
                triggeredBy,
                ts(report.startedAt()),
                ts(report.completedAt()),
                report.status(),
                report.totalRows(),
                report.insertedCount(),
                report.updatedCount(),
                report.failedCount(),
                mapper.writeValueAsString(report.errors()));
    }

    /** Most recent runs first. */
    List<VisitorImportReport> recent(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visitor_import_run ORDER BY started_at DESC LIMIT ?", (rs, i) -> map(rs), limit);
    }

    Optional<VisitorImportReport> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visitor_import_run WHERE id = ?", (rs, i) -> map(rs), id).stream().findFirst();
    }

    private VisitorImportReport map(ResultSet rs) throws SQLException {
        return new VisitorImportReport(
                rs.getObject("id", UUID.class),
                rs.getString("source"),
                rs.getString("filename"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                rs.getObject("completed_at", OffsetDateTime.class).toInstant(),
                rs.getInt("total_rows"),
                rs.getInt("inserted_count"),
                rs.getInt("updated_count"),
                rs.getInt("failed_count"),
                rs.getString("status"),
                readErrors(rs.getString("errors")));
    }

    private List<VisitorImportError> readErrors(String json) {
        return json == null ? List.of() : mapper.readValue(json, ERROR_LIST);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
