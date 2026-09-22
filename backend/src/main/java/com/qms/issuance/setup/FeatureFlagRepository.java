package com.qms.issuance.setup;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Feature flags a vertical profile turns on or off (SRS §3.3.6, CFG-001): a closed key vocabulary, no code branch. */
@Repository
class FeatureFlagRepository {

    private final JdbcTemplate jdbc;

    FeatureFlagRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Map<String, Boolean> all() {
        return jdbc.query("SELECT key, enabled FROM feature_flag", rs -> {
            Map<String, Boolean> result = new LinkedHashMap<>();
            while (rs.next()) result.put(rs.getString("key"), rs.getBoolean("enabled"));
            return result;
        });
    }

    void upsert(String key, boolean enabled, UUID actor, Instant now) {
        jdbc.update(
                "INSERT INTO feature_flag (key, enabled, updated_by, updated_at) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (key) DO UPDATE SET enabled = excluded.enabled, updated_by = excluded.updated_by, updated_at = excluded.updated_at",
                key, enabled, actor, ts(now));
    }

    void upsertAll(Map<String, Boolean> flags, UUID actor, Instant now) {
        List<Object[]> rows = new java.util.ArrayList<>();
        for (var entry : flags.entrySet()) rows.add(new Object[] {entry.getKey(), entry.getValue(), actor, ts(now)});
        jdbc.batchUpdate(
                "INSERT INTO feature_flag (key, enabled, updated_by, updated_at) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (key) DO UPDATE SET enabled = excluded.enabled, updated_by = excluded.updated_by, updated_at = excluded.updated_at",
                rows);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
