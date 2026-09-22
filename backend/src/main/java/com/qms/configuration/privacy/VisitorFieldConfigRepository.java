package com.qms.configuration.privacy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code visitor_field_config} table (FR-SEC-020, FR-SEC-023). */
@Repository
class VisitorFieldConfigRepository {

    record Row(String surface, String field, boolean visible, Instant updatedAt, UUID updatedBy) {}

    private final JdbcTemplate jdbc;

    VisitorFieldConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> list(String surface) {
        return jdbc.query(
                "SELECT surface, field, visible, updated_at, updated_by FROM visitor_field_config WHERE surface = ? ORDER BY field",
                (rs, i) -> new Row(
                        rs.getString("surface"), rs.getString("field"), rs.getBoolean("visible"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getObject("updated_by", UUID.class)),
                surface);
    }

    /** Every field of {@code surface} that is currently visible, or empty when the surface has no rows at all. */
    Set<String> visibleFields(String surface) {
        return Set.copyOf(jdbc.query(
                "SELECT field FROM visitor_field_config WHERE surface = ? AND visible", (rs, i) -> rs.getString("field"), surface));
    }

    boolean isVisible(String surface, String field) {
        return Boolean.TRUE.equals(jdbc.query(
                        "SELECT visible FROM visitor_field_config WHERE surface = ? AND field = ?",
                        (rs, i) -> rs.getBoolean("visible"),
                        surface, field)
                .stream()
                .findFirst()
                .orElse(Boolean.TRUE)); // A field this installation has not yet seeded defaults to on, never silently dropped.
    }

    void setVisible(String surface, String field, boolean visible, UUID updatedBy, Instant now) {
        jdbc.update(
                "UPDATE visitor_field_config SET visible = ?, updated_at = ?, updated_by = ? WHERE surface = ? AND field = ?",
                visible, ts(now), updatedBy, surface, field);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
