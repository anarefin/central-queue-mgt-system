package com.qms.configuration.catalogue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** SQL for Journey templates (ticket 31, FR-QUE-060), the admin side of what {@code issuance.JourneyRepository} reads. */
@Repository
class JourneyTemplateRepository {

    record Row(UUID id, UUID serviceGroupId, Map<String, String> nameI18n, boolean ordered, int displayOrder, boolean active) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    JourneyTemplateRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    Optional<Row> find(UUID id) {
        return jdbc.query(
                        "SELECT id, service_group_id, name_i18n, ordered, display_order, active FROM journey_template WHERE id = ?",
                        (rs, i) -> new Row(
                                rs.getObject("id", UUID.class), rs.getObject("service_group_id", UUID.class), names(rs.getString("name_i18n")), rs.getBoolean("ordered"),
                                rs.getInt("display_order"), rs.getBoolean("active")),
                        id)
                .stream().findFirst();
    }

    List<Row> ofGroup(UUID groupId) {
        return jdbc.query(
                "SELECT id, service_group_id, name_i18n, ordered, display_order, active FROM journey_template WHERE service_group_id = ? ORDER BY display_order, id",
                (rs, i) -> new Row(
                        rs.getObject("id", UUID.class), rs.getObject("service_group_id", UUID.class), names(rs.getString("name_i18n")), rs.getBoolean("ordered"), rs.getInt("display_order"),
                        rs.getBoolean("active")),
                groupId);
    }

    record StopRow(int seq, UUID serviceId, Map<String, String> serviceNames) {}

    List<StopRow> stopsOf(UUID templateId) {
        return jdbc.query(
                "SELECT jts.seq, jts.service_id, sv.name_i18n FROM journey_template_stop jts JOIN service sv ON sv.id = jts.service_id"
                        + " WHERE jts.template_id = ? ORDER BY jts.seq",
                (rs, i) -> new StopRow(rs.getInt("seq"), rs.getObject("service_id", UUID.class), names(rs.getString("name_i18n"))),
                templateId);
    }

    /** The Site and active flag of a Service group (own record, distinct from {@code CatalogueRepository}'s, ticket 31 convention). */
    record GroupInfo(UUID siteId, boolean active) {}

    Optional<GroupInfo> group(UUID groupId) {
        return jdbc.query(
                        "SELECT site_id, active FROM service_group WHERE id = ?",
                        (rs, i) -> new GroupInfo(rs.getObject("site_id", UUID.class), rs.getBoolean("active")),
                        groupId)
                .stream().findFirst();
    }

    UUID insert(UUID groupId, Map<String, String> nameI18n, boolean ordered, int displayOrder, List<UUID> serviceIds, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO journey_template (id, service_group_id, name_i18n, ordered, display_order, active, created_at) VALUES (?, ?, ?::jsonb, ?, ?, true, ?)",
                id, groupId, mapper.writeValueAsString(nameI18n), ordered, displayOrder, ts(now));
        int seq = 1;
        for (UUID serviceId : serviceIds) {
            jdbc.update("INSERT INTO journey_template_stop (id, template_id, service_id, seq) VALUES (?, ?, ?, ?)", UUID.randomUUID(), id, serviceId, seq);
            seq++;
        }
        return id;
    }

    void setActive(UUID id, boolean active) {
        jdbc.update("UPDATE journey_template SET active = ? WHERE id = ?", active, id);
    }

    /** Whether every Service named belongs to {@code groupId} and is active (a template's stops must all be one group's own offer). */
    boolean allBelongToGroupAndActive(UUID groupId, List<UUID> serviceIds) {
        if (serviceIds.isEmpty()) return false;
        List<UUID> valid = new ArrayList<>();
        jdbc.query(
                connection -> {
                    var ps = connection.prepareStatement("SELECT id FROM service WHERE id = ANY (?) AND service_group_id = ? AND active");
                    ps.setArray(1, connection.createArrayOf("uuid", serviceIds.toArray()));
                    ps.setObject(2, groupId);
                    return ps;
                },
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> valid.add(rs.getObject("id", UUID.class)));
        return valid.size() == serviceIds.stream().distinct().count() && valid.containsAll(serviceIds);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> names(String json) {
        return new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
