package com.qms.identity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The only persisted authentication state (API-014). Only SHA-256 hashes of the opaque tokens are stored. */
@Repository
class RefreshTokenRepository {

    record Row(UUID id, UUID familyId, UUID userId, Instant expiresAt, Instant usedAt, Instant revokedAt) {}

    private final JdbcTemplate jdbc;

    RefreshTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, UUID familyId, UUID userId, String tokenHash, Instant issuedAt, Instant expiresAt) {
        jdbc.update(
                "INSERT INTO refresh_tokens (id, family_id, user_id, token_hash, issued_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, familyId, userId, tokenHash, ts(issuedAt), ts(expiresAt));
    }

    /** Locks the row so two simultaneous refreshes of one token are serialised, and the second sees it as used. */
    Optional<Row> lockByHash(String tokenHash) {
        return jdbc.query(
                        "SELECT id, family_id, user_id, expires_at, used_at, revoked_at FROM refresh_tokens WHERE token_hash = ? FOR UPDATE",
                        (rs, i) -> new Row(
                                rs.getObject("id", UUID.class),
                                rs.getObject("family_id", UUID.class),
                                rs.getObject("user_id", UUID.class),
                                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                                instant(rs.getObject("used_at", OffsetDateTime.class)),
                                instant(rs.getObject("revoked_at", OffsetDateTime.class))),
                        tokenHash)
                .stream()
                .findFirst();
    }

    void markUsed(UUID id, Instant now) {
        jdbc.update("UPDATE refresh_tokens SET used_at = ? WHERE id = ?", ts(now), id);
    }

    int revokeFamily(UUID familyId, Instant now) {
        return jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL", ts(now), familyId);
    }

    /** Ends every session of a user: disabling the account, or changing its password (FR-CFG-104). */
    int revokeAllForUser(UUID userId, Instant now) {
        return jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", ts(now), userId);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
