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

    /** One CSV row's outcome: {@code inserted} is false when a visitor with that {@code external_code} already existed and was updated instead. */
    record UpsertResult(UUID id, boolean inserted) {}

    /**
     * CSV import's upsert (FR-INT-011): a fresh visitor is inserted, and one already known by this {@code
     * external_code} (V18's unique index) is updated in place instead, never duplicated. This is what makes a
     * CSV-imported visitor findable through the exact same {@link VisitorDirectory} a walk-in's pass reference already
     * is (FR-INT-010) — both are rows of the one table {@link LocalVisitorDirectory} reads.
     */
    UpsertResult upsertByExternalCode(String externalCode, String name, String phone, String email, String category, Instant now) {
        return jdbc.query(
                        "INSERT INTO visitor (id, external_code, name, category, phone, email, created_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT (external_code) WHERE external_code IS NOT NULL DO UPDATE SET "
                                + "name = EXCLUDED.name, phone = EXCLUDED.phone, email = EXCLUDED.email, category = EXCLUDED.category "
                                + "RETURNING id, (xmax = 0) AS inserted",
                        (rs, i) -> new UpsertResult(rs.getObject("id", UUID.class), rs.getBoolean("inserted")),
                        UUID.randomUUID(), externalCode, name, category, phone, email, ts(now))
                .stream()
                .findFirst()
                .orElseThrow();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
