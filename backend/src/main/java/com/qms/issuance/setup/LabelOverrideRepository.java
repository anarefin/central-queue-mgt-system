package com.qms.issuance.setup;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Terminology remapping's currently-effective overrides (SRS §3.2): the value each label key resolves to, one row
 * per (key, lang). Seeded in bulk when a profile is applied or reset, and editable one key at a time afterwards
 * (CFG-003).
 */
@Repository
class LabelOverrideRepository {

    private final JdbcTemplate jdbc;

    LabelOverrideRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every currently-effective override for one language, key to value. */
    Map<String, String> forLanguage(String lang) {
        return jdbc.query("SELECT key, value FROM label_override WHERE lang = ?", rs -> {
            Map<String, String> result = new java.util.LinkedHashMap<>();
            while (rs.next()) result.put(rs.getString("key"), rs.getString("value"));
            return result;
        }, lang);
    }

    void upsert(String key, String lang, String value, UUID actor, Instant now) {
        jdbc.update(
                "INSERT INTO label_override (key, lang, value, updated_by, updated_at) VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT (key, lang) DO UPDATE SET value = excluded.value, updated_by = excluded.updated_by, updated_at = excluded.updated_at",
                key, lang, value, actor, ts(now));
    }

    /** Bulk-seeds every (key, lang) a profile carries, overwriting any prior override for exactly those pairs. */
    void upsertAll(Map<String, Map<String, String>> labels, UUID actor, Instant now) {
        List<Object[]> rows = new java.util.ArrayList<>();
        for (var keyEntry : labels.entrySet()) {
            for (var langEntry : keyEntry.getValue().entrySet()) {
                rows.add(new Object[] {keyEntry.getKey(), langEntry.getKey(), langEntry.getValue(), actor, ts(now)});
            }
        }
        jdbc.batchUpdate(
                "INSERT INTO label_override (key, lang, value, updated_by, updated_at) VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT (key, lang) DO UPDATE SET value = excluded.value, updated_by = excluded.updated_by, updated_at = excluded.updated_at",
                rows);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
