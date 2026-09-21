package com.qms.reporting;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code reporting.retention_policy}'s own read/write path (ticket 53, FR-SEC-032). */
@Repository
class RetentionPolicyRepository {

    record Row(String dataClass, int retentionMonths, String mode, Instant updatedAt, UUID updatedBy) {}

    private static final String SELECT = "SELECT data_class, retention_months, mode, updated_at, updated_by FROM reporting.retention_policy";

    private final JdbcTemplate jdbc;

    RetentionPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> list() {
        return jdbc.query(SELECT + " ORDER BY data_class", RetentionPolicyRepository::map);
    }

    Optional<Row> find(String dataClass) {
        return jdbc.query(SELECT + " WHERE data_class = ?", RetentionPolicyRepository::map, dataClass).stream().findFirst();
    }

    void update(String dataClass, int retentionMonths, String mode, UUID updatedBy, Instant now) {
        jdbc.update(
                "UPDATE reporting.retention_policy SET retention_months = ?, mode = ?, updated_at = ?, updated_by = ? WHERE data_class = ?",
                retentionMonths, mode, Timestamp.from(now), updatedBy, dataClass);
    }

    private static Row map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        Object updatedByRaw = rs.getObject("updated_by");
        return new Row(
                rs.getString("data_class"),
                rs.getInt("retention_months"),
                rs.getString("mode"),
                updatedAt == null ? null : updatedAt.toInstant(),
                updatedByRaw == null ? null : (UUID) updatedByRaw);
    }
}
