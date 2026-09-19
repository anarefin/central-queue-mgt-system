package com.qms.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class UserRepository {

    /** Result of counting one more failed sign-in: {@code locked} is true when this attempt triggered the lock. */
    record FailureOutcome(int attempts, Instant lockedUntil, boolean locked) {}

    private static final String COLUMNS =
            "id, username, password_hash, display_name, preferred_language, active, failed_attempts, locked_until, password_changed_at, created_at";

    private final JdbcTemplate jdbc;

    UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID insert(String username, String passwordHash, String displayName, String preferredLanguage, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language, password_changed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, username, passwordHash, displayName, preferredLanguage, ts(now), ts(now), ts(now));
        recordHistory(id, passwordHash, now);
        return id;
    }

    Optional<UserAccount> findByUsername(String username) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE lower(username) = lower(?)", (rs, i) -> map(rs), username)
                .stream().findFirst();
    }

    Optional<UserAccount> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE id = ?", (rs, i) -> map(rs), id).stream().findFirst();
    }

    void updateProfile(UUID id, String displayName, String preferredLanguage, Instant now) {
        jdbc.update("UPDATE users SET display_name = ?, preferred_language = ?, updated_at = ? WHERE id = ?", displayName, preferredLanguage, ts(now), id);
    }

    /** Keyset page ordered by lower(username); afterUsernameLower is the last username of the previous page. */
    List<UserAccount> page(String afterUsernameLower, int limit) {
        if (afterUsernameLower == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM users ORDER BY lower(username) LIMIT ?", (rs, i) -> map(rs), limit);
        }
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM users WHERE lower(username) > ? ORDER BY lower(username) LIMIT ?", (rs, i) -> map(rs), afterUsernameLower, limit);
    }

    long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Atomically counts a failed sign-in and locks the account when it reaches {@code maxAttempts}. One statement, so
     * concurrent attempts cannot slip past the threshold by racing a read-then-write.
     */
    FailureOutcome recordFailure(UUID id, int maxAttempts, Duration lockFor, Instant now) {
        Instant lockUntil = now.plus(lockFor).truncatedTo(ChronoUnit.MICROS);
        return jdbc.query(
                "UPDATE users SET"
                        + " failed_attempts = CASE WHEN failed_attempts + 1 >= ? THEN 0 ELSE failed_attempts + 1 END,"
                        + " locked_until = CASE WHEN failed_attempts + 1 >= ? THEN ? ELSE locked_until END,"
                        + " updated_at = ? WHERE id = ? RETURNING failed_attempts, locked_until",
                rs -> {
                    rs.next();
                    OffsetDateTime locked = rs.getObject("locked_until", OffsetDateTime.class);
                    Instant lockedUntil = locked == null ? null : locked.toInstant();
                    return new FailureOutcome(rs.getInt("failed_attempts"), lockedUntil, lockUntil.equals(lockedUntil));
                },
                maxAttempts, maxAttempts, ts(lockUntil), ts(now), id);
    }

    void resetFailures(UUID id, Instant now) {
        jdbc.update(
                "UPDATE users SET failed_attempts = 0, locked_until = NULL, updated_at = ?"
                        + " WHERE id = ? AND (failed_attempts <> 0 OR locked_until IS NOT NULL)",
                ts(now), id);
    }

    void setActive(UUID id, boolean active) {
        jdbc.update("UPDATE users SET active = ?, updated_at = now() WHERE id = ?", active, id);
    }

    void changePassword(UUID id, String passwordHash, Instant now) {
        jdbc.update(
                "UPDATE users SET password_hash = ?, password_changed_at = ?, failed_attempts = 0, locked_until = NULL, updated_at = ? WHERE id = ?",
                passwordHash, ts(now), ts(now), id);
        recordHistory(id, passwordHash, now);
    }

    /** Most recent password hashes, newest first, for the reuse check. */
    List<String> recentPasswordHashes(UUID id, int limit) {
        return jdbc.queryForList(
                "SELECT password_hash FROM user_password_history WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                String.class, id, limit);
    }

    private void recordHistory(UUID userId, String passwordHash, Instant now) {
        jdbc.update(
                "INSERT INTO user_password_history (id, user_id, password_hash, created_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), userId, passwordHash, ts(now));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UserAccount map(ResultSet rs) throws SQLException {
        OffsetDateTime locked = rs.getObject("locked_until", OffsetDateTime.class);
        return new UserAccount(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getString("preferred_language"),
                rs.getBoolean("active"),
                rs.getInt("failed_attempts"),
                locked == null ? null : locked.toInstant(),
                rs.getObject("password_changed_at", OffsetDateTime.class).toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
