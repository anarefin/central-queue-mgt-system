package com.qms.configuration.versioning;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** SQL for the {@code config_version} table (SRS §18.2). Append-only: nothing here updates or deletes a row. */
@Repository
class ConfigVersionRepository {

    private static final String COLUMNS = "id, entity, entity_id, payload::text AS payload, changed_by, changed_at";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final RowMapper<ConfigVersion> rows;

    ConfigVersionRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rows = (rs, i) -> new ConfigVersion(
                rs.getObject("id", UUID.class),
                rs.getString("entity"),
                rs.getObject("entity_id", UUID.class),
                payload(rs.getString("payload")),
                rs.getObject("changed_by", UUID.class),
                rs.getObject("changed_at", OffsetDateTime.class).toInstant());
    }

    void insert(ConfigVersion version) {
        jdbc.update(
                "INSERT INTO config_version (id, entity, entity_id, payload, changed_by, changed_at) VALUES (?, ?, ?, ?::jsonb, ?, ?)",
                version.id(), version.entity(), version.entityId(), mapper.writeValueAsString(version.payload()), version.changedBy(), ts(version.changedAt()));
    }

    /** Newest first, so index 0 is what a revert to "the previous version" means. */
    List<ConfigVersion> history(String entity, UUID entityId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM config_version WHERE entity = ? AND entity_id = ? ORDER BY changed_at DESC", rows, entity, entityId);
    }

    Optional<ConfigVersion> get(String entity, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM config_version WHERE entity = ? AND id = ?", rows, entity, id).stream().findFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(String json) {
        return new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
