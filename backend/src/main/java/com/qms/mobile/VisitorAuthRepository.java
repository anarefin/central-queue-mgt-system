package com.qms.mobile;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * A registered visitor's own auth state (ticket 41): the {@code visitor} row itself (shared with {@code
 * com.qms.issuance}, which owns walk-in registration and CSV import of the same table — reading and writing a table
 * across a package boundary by plain SQL, rather than reusing that package's own package-private repository class,
 * is the established shape here, the same one {@code com.qms.notification.EmailChannel} already reads {@code
 * visitor.email} through), its OTPs, and its refresh tokens.
 */
@Repository
class VisitorAuthRepository {

    record VisitorRow(UUID id, String name, String email) {}

    record OtpRow(UUID id, String codeHash, int attempts, Instant expiresAt, Instant consumedAt) {}

    record RefreshRow(UUID id, UUID familyId, UUID visitorId, Instant expiresAt, Instant usedAt, Instant revokedAt) {}

    private final JdbcTemplate jdbc;

    VisitorAuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<VisitorRow> findByEmail(String email) {
        return jdbc.query(
                        "SELECT id, name, email FROM visitor WHERE lower(email) = lower(?)",
                        (rs, i) -> new VisitorRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email")),
                        email)
                .stream()
                .findFirst();
    }

    /** A brand-new registered visitor, known so far only by the email they signed in with. */
    UUID insertVisitor(String email, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO visitor (id, email, created_at) VALUES (?, ?, ?)", id, email, ts(now));
        return id;
    }

    void markEmailVerified(UUID visitorId, Instant now) {
        jdbc.update("UPDATE visitor SET email_verified_at = ? WHERE id = ? AND email_verified_at IS NULL", ts(now), visitorId);
    }

    Optional<VisitorRow> findById(UUID id) {
        return jdbc.query(
                        "SELECT id, name, email FROM visitor WHERE id = ?",
                        (rs, i) -> new VisitorRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email")),
                        id)
                .stream()
                .findFirst();
    }

    // ---- OTPs -------------------------------------------------------------------------------------------------

    /** Serialises the rate-limit check and the insert it guards for one email, the same {@code IssuanceGate} pattern. */
    void lock(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, key);
    }

    int countRequestsSince(String email, Instant since) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM visitor_otp WHERE lower(email) = lower(?) AND requested_at >= ?", Integer.class, email, ts(since));
        return count == null ? 0 : count;
    }

    UUID insertOtp(String email, String codeHash, Instant requestedAt, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO visitor_otp (id, email, code_hash, requested_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                id, email, codeHash, ts(requestedAt), ts(expiresAt));
        return id;
    }

    /** The most recent still-open (not consumed) OTP for this email, locked for the length of the verify attempt. */
    Optional<OtpRow> lockLatestOpen(String email) {
        return jdbc.query(
                        "SELECT id, code_hash, attempts, expires_at, consumed_at FROM visitor_otp "
                                + "WHERE lower(email) = lower(?) AND consumed_at IS NULL ORDER BY requested_at DESC LIMIT 1 FOR UPDATE",
                        (rs, i) -> new OtpRow(
                                rs.getObject("id", UUID.class),
                                rs.getString("code_hash"),
                                rs.getInt("attempts"),
                                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                                instant(rs.getObject("consumed_at", OffsetDateTime.class))),
                        email)
                .stream()
                .findFirst();
    }

    void incrementAttempts(UUID id) {
        jdbc.update("UPDATE visitor_otp SET attempts = attempts + 1 WHERE id = ?", id);
    }

    void markConsumed(UUID id, Instant now) {
        jdbc.update("UPDATE visitor_otp SET consumed_at = ? WHERE id = ?", ts(now), id);
    }

    // ---- refresh tokens -----------------------------------------------------------------------------------------

    void insertRefreshToken(UUID id, UUID familyId, UUID visitorId, String tokenHash, Instant issuedAt, Instant expiresAt) {
        jdbc.update(
                "INSERT INTO visitor_refresh_tokens (id, family_id, visitor_id, token_hash, issued_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, familyId, visitorId, tokenHash, ts(issuedAt), ts(expiresAt));
    }

    Optional<RefreshRow> lockByHash(String tokenHash) {
        return jdbc.query(
                        "SELECT id, family_id, visitor_id, expires_at, used_at, revoked_at FROM visitor_refresh_tokens WHERE token_hash = ? FOR UPDATE",
                        (rs, i) -> new RefreshRow(
                                rs.getObject("id", UUID.class),
                                rs.getObject("family_id", UUID.class),
                                rs.getObject("visitor_id", UUID.class),
                                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                                instant(rs.getObject("used_at", OffsetDateTime.class)),
                                instant(rs.getObject("revoked_at", OffsetDateTime.class))),
                        tokenHash)
                .stream()
                .findFirst();
    }

    void markRefreshUsed(UUID id, Instant now) {
        jdbc.update("UPDATE visitor_refresh_tokens SET used_at = ? WHERE id = ?", ts(now), id);
    }

    void revokeFamily(UUID familyId, Instant now) {
        jdbc.update("UPDATE visitor_refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL", ts(now), familyId);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
