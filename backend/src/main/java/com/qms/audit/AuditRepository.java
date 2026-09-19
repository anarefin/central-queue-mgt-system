package com.qms.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Read side of the audit log. There is deliberately no way to change or remove an entry from here. */
@Repository
class AuditRepository {

    private static final String COLUMNS =
            "id, actor_id, actor_role, action, entity, entity_id, before::text AS before, after::text AS after, ip, device, reason, trace_id, occurred_at";

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper mapper;

    AuditRepository(NamedParameterJdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    AuditPage search(AuditFilter filter, AuditCursor cursor, int limit) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM audit_log WHERE 1 = 1");
        var params = new MapSqlParameterSource();

        if (filter.actorId() != null) {
            sql.append(" AND actor_id = :actorId");
            params.addValue("actorId", filter.actorId());
        }
        if (filter.action() != null && !filter.action().isBlank()) {
            if (filter.action().endsWith("*")) {
                sql.append(" AND action LIKE :action ESCAPE '\\'");
                params.addValue("action", escapeLike(filter.action().substring(0, filter.action().length() - 1)) + "%");
            } else {
                sql.append(" AND action = :action");
                params.addValue("action", filter.action());
            }
        }
        if (filter.entity() != null && !filter.entity().isBlank()) {
            sql.append(" AND entity = :entity");
            params.addValue("entity", filter.entity());
        }
        if (filter.entityId() != null) {
            sql.append(" AND entity_id = :entityId");
            params.addValue("entityId", filter.entityId());
        }
        if (filter.from() != null) {
            sql.append(" AND occurred_at >= :from");
            params.addValue("from", filter.from());
        }
        if (filter.to() != null) {
            sql.append(" AND occurred_at < :to");
            params.addValue("to", filter.to());
        }
        if (cursor != null) {
            sql.append(" AND (occurred_at, id) < (:cursorAt, :cursorId)");
            params.addValue("cursorAt", cursor.occurredAt());
            params.addValue("cursorId", cursor.id());
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT :limit");
        params.addValue("limit", limit + 1);

        List<AuditEntry> rows = new ArrayList<>(jdbc.query(sql.toString(), params, (rs, i) -> map(rs)));
        String next = null;
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            AuditEntry last = rows.getLast();
            next = AuditCursor.encode(last.occurredAt(), last.id());
        }
        return new AuditPage(List.copyOf(rows), next);
    }

    /** A {@code %} or {@code _} typed into a filter must match itself, not act as a wildcard. */
    private static String escapeLike(String literal) {
        return literal.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private AuditEntry map(ResultSet rs) throws SQLException {
        return new AuditEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_role"),
                rs.getString("action"),
                rs.getString("entity"),
                rs.getObject("entity_id", UUID.class),
                readMap(rs.getString("before")),
                readMap(rs.getString("after")),
                rs.getString("ip"),
                rs.getString("device"),
                rs.getString("reason"),
                rs.getString("trace_id"),
                rs.getObject("occurred_at", OffsetDateTime.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        return json == null ? null : mapper.readValue(json, Map.class);
    }
}
