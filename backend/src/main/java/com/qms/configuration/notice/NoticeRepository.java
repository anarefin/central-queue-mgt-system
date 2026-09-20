package com.qms.configuration.notice;

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

/** SQL for notice-board content. */
@Repository
class NoticeRepository {

    private static final String NOTICE =
            "SELECT id, zone_id, type, content_i18n, starts_at, ends_at, sort_order, active, created_by, created_at, updated_at FROM notice";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final RowMapper<Notice> notices;

    NoticeRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.notices = (rs, i) -> new Notice(
                rs.getObject("id", UUID.class),
                rs.getObject("zone_id", UUID.class),
                rs.getString("type"),
                content(rs.getString("content_i18n")),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                rs.getInt("sort_order"),
                rs.getBoolean("active"),
                rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    List<Notice> forZone(UUID zoneId) {
        return jdbc.query(NOTICE + " WHERE zone_id = ? ORDER BY sort_order, starts_at, id", notices, zoneId);
    }

    Optional<Notice> find(UUID id) {
        return jdbc.query(NOTICE + " WHERE id = ?", notices, id).stream().findFirst();
    }

    /** The zone's notice-board playlist active right now (FR-DSP-006): active, and inside its own date window. */
    List<Notice> activeForZone(UUID zoneId, Instant now) {
        return jdbc.query(
                NOTICE + " WHERE zone_id = ? AND active AND starts_at <= ? AND ends_at > ? ORDER BY sort_order, starts_at, id",
                notices, zoneId, ts(now), ts(now));
    }

    void insert(Notice n) {
        jdbc.update(
                "INSERT INTO notice (id, zone_id, type, content_i18n, starts_at, ends_at, sort_order, active, created_by, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, true, ?, ?, ?)",
                n.id(), n.zoneId(), n.type(), mapper.writeValueAsString(n.contentI18n()), ts(n.startsAt()), ts(n.endsAt()), n.sortOrder(),
                n.createdBy(), ts(n.createdAt()), ts(n.updatedAt()));
    }

    void update(Notice n) {
        jdbc.update(
                "UPDATE notice SET zone_id = ?, type = ?, content_i18n = ?::jsonb, starts_at = ?, ends_at = ?, sort_order = ?, updated_at = ? WHERE id = ?",
                n.zoneId(), n.type(), mapper.writeValueAsString(n.contentI18n()), ts(n.startsAt()), ts(n.endsAt()), n.sortOrder(), ts(n.updatedAt()), n.id());
    }

    void setActive(UUID id, boolean active, Instant now) {
        jdbc.update("UPDATE notice SET active = ?, updated_at = ? WHERE id = ?", active, ts(now), id);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> content(String json) {
        return new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
