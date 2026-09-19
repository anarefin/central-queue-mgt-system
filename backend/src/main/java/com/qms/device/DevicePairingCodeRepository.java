package com.qms.device;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Short-lived, single-use pairing codes (FR-OPS-011). Only the SHA-256 hash of the raw code is stored (API-014 precedent). */
@Repository
class DevicePairingCodeRepository {

    record Row(UUID id, String kind, UUID siteId, UUID zoneId, String label, Instant expiresAt, Instant usedAt) {}

    private final JdbcTemplate jdbc;

    DevicePairingCodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, String codeHash, String kind, UUID siteId, UUID zoneId, String label, Instant issuedAt, Instant expiresAt) {
        jdbc.update(
                "INSERT INTO device_pairing_code (id, code_hash, kind, site_id, zone_id, label, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, codeHash, kind, siteId, zoneId, label, ts(expiresAt), ts(issuedAt));
    }

    /** Locks the row so two simultaneous redemptions of one code are serialised, and the second sees it as used. */
    Optional<Row> lockByHash(String codeHash) {
        return jdbc.query(
                        "SELECT id, kind, site_id, zone_id, label, expires_at, used_at FROM device_pairing_code WHERE code_hash = ? FOR UPDATE",
                        (rs, i) -> new Row(
                                rs.getObject("id", UUID.class),
                                rs.getString("kind"),
                                rs.getObject("site_id", UUID.class),
                                rs.getObject("zone_id", UUID.class),
                                rs.getString("label"),
                                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                                instant(rs)),
                        codeHash)
                .stream()
                .findFirst();
    }

    void markUsed(UUID id, Instant now) {
        jdbc.update("UPDATE device_pairing_code SET used_at = ? WHERE id = ?", ts(now), id);
    }

    private static Instant instant(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject("used_at", OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
