package com.qms.configuration.priority;

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

/** SQL for Priority classes and the routing strategy of Service groups. */
@Repository
class PriorityRepository {

    private static final String CLASS =
            "SELECT id, name_i18n, headstart_minutes, max_wait_minutes, token_prefix_override, is_default, active, created_at, updated_at FROM priority_class";

    /** The site and group a strategy belongs to, for scoping. */
    record GroupRef(UUID id, UUID siteId) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final RowMapper<PriorityClass> classes;

    PriorityRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.classes = (rs, i) -> new PriorityClass(
                rs.getObject("id", UUID.class),
                names(rs.getString("name_i18n")),
                rs.getInt("headstart_minutes"),
                rs.getObject("max_wait_minutes", Integer.class),
                rs.getString("token_prefix_override"),
                rs.getBoolean("is_default"),
                rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    // ---- priority classes --------------------------------------------------------------------------------------

    /** The default class first, then by Head start and creation. */
    List<PriorityClass> all() {
        return jdbc.query(CLASS + " ORDER BY is_default DESC, headstart_minutes, created_at, id", classes);
    }

    Optional<PriorityClass> find(UUID id) {
        return jdbc.query(CLASS + " WHERE id = ?", classes, id).stream().findFirst();
    }

    void insert(PriorityClass c) {
        jdbc.update(
                "INSERT INTO priority_class (id, name_i18n, headstart_minutes, max_wait_minutes, token_prefix_override, is_default, active, created_at, updated_at)"
                        + " VALUES (?, ?::jsonb, ?, ?, ?, false, true, ?, ?)",
                c.id(), mapper.writeValueAsString(c.nameI18n()), c.headstartMinutes(), c.maxWaitMinutes(), c.tokenPrefixOverride(), ts(c.createdAt()), ts(c.updatedAt()));
    }

    void update(PriorityClass c) {
        jdbc.update(
                "UPDATE priority_class SET name_i18n = ?::jsonb, headstart_minutes = ?, max_wait_minutes = ?, token_prefix_override = ?, updated_at = ? WHERE id = ?",
                mapper.writeValueAsString(c.nameI18n()), c.headstartMinutes(), c.maxWaitMinutes(), c.tokenPrefixOverride(), ts(c.updatedAt()), c.id());
    }

    void setActive(UUID id, boolean active, Instant now) {
        jdbc.update("UPDATE priority_class SET active = ?, updated_at = ? WHERE id = ?", active, ts(now), id);
    }

    // ---- routing strategy --------------------------------------------------------------------------------------

    Optional<GroupRef> group(UUID groupId) {
        return jdbc.query("SELECT id, site_id FROM service_group WHERE id = ?", (rs, i) -> new GroupRef(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class)), groupId)
                .stream().findFirst();
    }

    Optional<String> strategy(UUID groupId) {
        return jdbc.query("SELECT strategy FROM routing_strategy WHERE service_group_id = ?", (rs, i) -> rs.getString("strategy"), groupId).stream().findFirst();
    }

    void setStrategy(UUID groupId, String strategy, Instant now) {
        jdbc.update(
                "INSERT INTO routing_strategy (service_group_id, strategy, updated_at) VALUES (?, ?, ?)"
                        + " ON CONFLICT (service_group_id) DO UPDATE SET strategy = EXCLUDED.strategy, updated_at = EXCLUDED.updated_at",
                groupId, strategy, ts(now));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> names(String json) {
        return new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
