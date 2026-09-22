package com.qms.issuance.setup;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * A small keyed settings table for the setup wizard's own state (SRS §26.2): which vertical profile is active and
 * when it was applied, and when the installation went live. Each key holds one JSON value.
 */
@Repository
class SystemSettingRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    SystemSettingRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> get(String key) {
        org.springframework.jdbc.core.RowMapper<Map<String, Object>> row =
                (rs, i) -> new LinkedHashMap<String, Object>(mapper.readValue(rs.getString("value"), LinkedHashMap.class));
        return jdbc.query("SELECT value::text AS value FROM system_setting WHERE key = ?", row, key).stream().findFirst();
    }

    void set(String key, Map<String, Object> value, UUID actor, Instant now) {
        jdbc.update(
                "INSERT INTO system_setting (key, value, updated_by, updated_at) VALUES (?, ?::jsonb, ?, ?)"
                        + " ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_by = excluded.updated_by, updated_at = excluded.updated_at",
                key, mapper.writeValueAsString(value), actor, ts(now));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
