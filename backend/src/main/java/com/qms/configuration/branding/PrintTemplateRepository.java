package com.qms.configuration.branding;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** The single, organisation-wide {@code print_template} row (id is always {@code true}, FR-CFG-031). */
@Repository
class PrintTemplateRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    PrintTemplateRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** {@link PrintTemplate#DEFAULT}'s values would never actually surface: V22 seeds the row on every install. */
    PrintTemplate get() {
        List<PrintTemplate> rows = jdbc.query(
                "SELECT fields::text AS fields, notice_line, updated_at, updated_by FROM print_template WHERE id = true",
                (rs, i) -> new PrintTemplate(
                        fields(rs.getString("fields")),
                        rs.getString("notice_line"),
                        rs.getObject("updated_at", OffsetDateTime.class) == null ? null : rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_by", UUID.class)));
        return rows.isEmpty() ? PrintTemplate.DEFAULT : rows.get(0);
    }

    void save(PrintTemplate template, UUID updatedBy, Instant now) {
        jdbc.update(
                "INSERT INTO print_template (id, fields, notice_line, updated_at, updated_by) VALUES (true, ?::jsonb, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET fields = EXCLUDED.fields, notice_line = EXCLUDED.notice_line, "
                        + "updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by",
                mapper.writeValueAsString(template.fields()), template.noticeLine(), ts(now), updatedBy);
    }

    @SuppressWarnings("unchecked")
    private List<String> fields(String json) {
        return List.copyOf((List<String>) (List<?>) mapper.readValue(json, List.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
