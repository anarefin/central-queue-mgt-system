package com.qms.configuration.approval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class ApprovalRepository {

    private static final String COLUMNS =
            "id, type, requested_by, payload::text AS payload, status, decided_by, decided_at, decision_reason, created_at";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    ApprovalRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    UUID insert(ApprovalType type, UUID requestedBy, Map<String, Object> payload, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO approval_requests (id, type, requested_by, payload, created_at) VALUES (?, ?, ?, ?::jsonb, ?)",
                id, type.wire(), requestedBy, mapper.writeValueAsString(payload), now.atOffset(ZoneOffset.UTC));
        return id;
    }

    Optional<ApprovalView> lock(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM approval_requests WHERE id = ? FOR UPDATE", (rs, i) -> map(rs), id).stream().findFirst();
    }

    Optional<ApprovalView> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM approval_requests WHERE id = ?", (rs, i) -> map(rs), id).stream().findFirst();
    }

    List<ApprovalView> byStatus(String status, List<String> types) {
        String marks = String.join(",", types.stream().map(t -> "?").toList());
        Object[] args = new Object[types.size() + 1];
        args[0] = status;
        for (int i = 0; i < types.size(); i++) args[i + 1] = types.get(i);
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM approval_requests WHERE status = ? AND type IN (" + marks + ") ORDER BY created_at DESC, id DESC LIMIT 200",
                (rs, i) -> map(rs), args);
    }

    List<ApprovalView> byRequester(UUID requestedBy) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM approval_requests WHERE requested_by = ? ORDER BY created_at DESC, id DESC LIMIT 200",
                (rs, i) -> map(rs), requestedBy);
    }

    void decide(UUID id, String status, UUID decidedBy, Instant now, String reason) {
        jdbc.update(
                "UPDATE approval_requests SET status = ?, decided_by = ?, decided_at = ?, decision_reason = ? WHERE id = ? AND status = 'pending'",
                status, decidedBy, now.atOffset(ZoneOffset.UTC), reason, id);
    }

    @SuppressWarnings("unchecked")
    private ApprovalView map(ResultSet rs) throws SQLException {
        OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
        return new ApprovalView(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getObject("requested_by", UUID.class),
                mapper.readValue(rs.getString("payload"), Map.class),
                rs.getString("status"),
                rs.getObject("decided_by", UUID.class),
                decidedAt == null ? null : decidedAt.toInstant(),
                rs.getString("decision_reason"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
