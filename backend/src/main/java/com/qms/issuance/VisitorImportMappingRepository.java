package com.qms.issuance;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The single, admin-set FR-INT-011 column mapping, one row (id is always {@code true}). */
@Repository
class VisitorImportMappingRepository {

    private final JdbcTemplate jdbc;

    VisitorImportMappingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@link VisitorImportMapping#DEFAULT} until an admin has ever saved one. */
    VisitorImportMapping get() {
        List<VisitorImportMapping> rows = jdbc.query(
                "SELECT external_code_column, name_column, phone_column, email_column, category_column FROM visitor_import_mapping WHERE id = true",
                (rs, i) -> new VisitorImportMapping(
                        rs.getString("external_code_column"),
                        rs.getString("name_column"),
                        rs.getString("phone_column"),
                        rs.getString("email_column"),
                        rs.getString("category_column")));
        return rows.isEmpty() ? VisitorImportMapping.DEFAULT : rows.get(0);
    }

    void save(VisitorImportMapping mapping, UUID updatedBy, Instant now) {
        jdbc.update(
                "INSERT INTO visitor_import_mapping (id, external_code_column, name_column, phone_column, email_column, category_column, updated_at, updated_by) "
                        + "VALUES (true, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET external_code_column = EXCLUDED.external_code_column, name_column = EXCLUDED.name_column, "
                        + "phone_column = EXCLUDED.phone_column, email_column = EXCLUDED.email_column, category_column = EXCLUDED.category_column, "
                        + "updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by",
                mapping.externalCodeColumn(), mapping.nameColumn(), mapping.phoneColumn(), mapping.emailColumn(), mapping.categoryColumn(), ts(now), updatedBy);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
