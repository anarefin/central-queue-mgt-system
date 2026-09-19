package com.qms.issuance;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The local {@code visitor} table: the v1 directory (FR-INT-010) and the record a walk-in registration writes (FR-ISS-021). */
@Repository
class VisitorRepository {

    /** A visitor row as the directory and the registration response need it. */
    record VisitorRow(UUID id, String externalCode, String name, String category, String phone, String email) {}

    private final JdbcTemplate jdbc;

    VisitorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A visitor known by exactly that code or that phone number (FR-INT-010). */
    Optional<VisitorRow> findByCodeOrPhone(String query) {
        return jdbc.query(
                        "SELECT id, external_code, name, category, phone, email FROM visitor WHERE external_code = ? OR phone = ? ORDER BY created_at DESC LIMIT 1",
                        (rs, i) -> new VisitorRow(
                                rs.getObject("id", UUID.class),
                                rs.getString("external_code"),
                                rs.getString("name"),
                                rs.getString("category"),
                                rs.getString("phone"),
                                rs.getString("email")),
                        query,
                        query)
                .stream()
                .findFirst();
    }

    UUID insert(String externalCode, String name, String phone, String email, String category, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO visitor (id, external_code, name, category, phone, email, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, externalCode, name, category, phone, email, ts(createdAt));
        return id;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
