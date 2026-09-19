package com.qms.configuration.breaks;

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

/** SQL for break types. */
@Repository
class BreakTypeRepository {

    private static final String TYPE = "SELECT id, name_i18n, max_minutes, active, created_at, updated_at FROM break_type";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final RowMapper<BreakType> types;

    BreakTypeRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.types = (rs, i) -> new BreakType(
                rs.getObject("id", UUID.class),
                names(rs.getString("name_i18n")),
                rs.getObject("max_minutes", Integer.class),
                rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    List<BreakType> all() {
        return jdbc.query(TYPE + " ORDER BY created_at, id", types);
    }

    Optional<BreakType> find(UUID id) {
        return jdbc.query(TYPE + " WHERE id = ?", types, id).stream().findFirst();
    }

    void insert(BreakType t) {
        jdbc.update(
                "INSERT INTO break_type (id, name_i18n, max_minutes, active, created_at, updated_at) VALUES (?, ?::jsonb, ?, true, ?, ?)",
                t.id(), mapper.writeValueAsString(t.nameI18n()), t.maxMinutes(), ts(t.createdAt()), ts(t.updatedAt()));
    }

    void update(BreakType t) {
        jdbc.update("UPDATE break_type SET name_i18n = ?::jsonb, max_minutes = ?, updated_at = ? WHERE id = ?", mapper.writeValueAsString(t.nameI18n()), t.maxMinutes(), ts(t.updatedAt()), t.id());
    }

    void setActive(UUID id, boolean active, Instant now) {
        jdbc.update("UPDATE break_type SET active = ?, updated_at = ? WHERE id = ?", active, ts(now), id);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> names(String json) {
        return new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
