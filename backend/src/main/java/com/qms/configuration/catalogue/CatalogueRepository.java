package com.qms.configuration.catalogue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL for service groups, services, counter links and outcome codes. Sites and counters are read only to know their
 * languages and whether they exist; this class never writes them.
 */
@Repository
class CatalogueRepository {

    private static final String GROUP =
            "SELECT g.id, g.site_id, g.name_i18n, g.token_prefix, g.display_order, g.active, g.created_at, g.updated_at, s.enabled_languages"
                    + " FROM service_group g JOIN site s ON s.id = g.site_id";
    private static final String SERVICE =
            "SELECT v.id, v.service_group_id, g.site_id, v.name_i18n, v.token_prefix, v.expected_minutes, v.sla_wait_minutes, v.channels, v.icon,"
                    + " v.display_order, v.requires_visitor_id, v.booking_mode, v.parallel_serving, v.parallel_limit, v.active, v.created_at, v.updated_at, s.enabled_languages"
                    + " FROM service v JOIN service_group g ON g.id = v.service_group_id JOIN site s ON s.id = g.site_id";
    private static final String OUTCOME =
            "SELECT o.id, o.service_id, g.site_id, o.code, o.label_i18n, o.display_order, o.active, o.created_at, o.updated_at, s.enabled_languages"
                    + " FROM outcome_code o JOIN service v ON v.id = o.service_id JOIN service_group g ON g.id = v.service_group_id JOIN site s ON s.id = g.site_id";

    /** The language settings of a site, which decide which names a catalogue record may carry. */
    record SiteLanguages(String defaultLanguage, List<String> enabled, boolean active) {}

    /** What a counter link needs to know about a counter. */
    record CounterInfo(UUID siteId, boolean active) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    CatalogueRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---- sites and counters (read only) -------------------------------------------------------------------------

    Optional<SiteLanguages> siteLanguages(UUID siteId) {
        return jdbc.query(
                        "SELECT default_language, enabled_languages, active FROM site WHERE id = ?",
                        (rs, i) -> new SiteLanguages(rs.getString("default_language"), strings(rs.getString("enabled_languages")), rs.getBoolean("active")),
                        siteId)
                .stream().findFirst();
    }

    Optional<CounterInfo> counter(UUID counterId) {
        return jdbc.query(
                        "SELECT z.site_id, c.active FROM counter c JOIN zone z ON z.id = c.zone_id WHERE c.id = ?",
                        (rs, i) -> new CounterInfo(rs.getObject("site_id", UUID.class), rs.getBoolean("active")),
                        counterId)
                .stream().findFirst();
    }

    List<CounterOption> counterOptions(UUID siteId) {
        return jdbc.query(
                "SELECT c.id, c.zone_id, z.name AS zone_name, c.label FROM counter c JOIN zone z ON z.id = c.zone_id"
                        + " WHERE z.site_id = ? AND c.active AND z.active ORDER BY z.display_order, lower(z.name), lower(c.label), c.id",
                (rs, i) -> new CounterOption(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class), rs.getString("zone_name"), rs.getString("label")),
                siteId);
    }

    // ---- service groups -----------------------------------------------------------------------------------------

    List<ServiceGroup> groupsOfSite(UUID siteId) {
        return jdbc.query(GROUP + " WHERE g.site_id = ? ORDER BY g.display_order, g.token_prefix, g.id", (rs, i) -> group(rs), siteId);
    }

    Optional<ServiceGroup> group(UUID id) {
        return jdbc.query(GROUP + " WHERE g.id = ?", (rs, i) -> group(rs), id).stream().findFirst();
    }

    void insert(ServiceGroup group) {
        jdbc.update(
                "INSERT INTO service_group (id, site_id, name_i18n, token_prefix, display_order, active, created_at, updated_at) VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?)",
                group.id(), group.siteId(), json(group.nameI18n()), group.tokenPrefix(), group.displayOrder(), group.active(), ts(group.createdAt()), ts(group.updatedAt()));
    }

    void update(ServiceGroup group) {
        jdbc.update(
                "UPDATE service_group SET name_i18n = ?::jsonb, token_prefix = ?, display_order = ?, updated_at = ? WHERE id = ?",
                json(group.nameI18n()), group.tokenPrefix(), group.displayOrder(), ts(group.updatedAt()), group.id());
    }

    void setGroupActive(UUID id, boolean active, Instant now) {
        jdbc.update("UPDATE service_group SET active = ?, updated_at = ? WHERE id = ?", active, ts(now), id);
    }

    // ---- services -----------------------------------------------------------------------------------------------

    List<ServiceEntry> servicesOfGroup(UUID groupId) {
        return jdbc.query(SERVICE + " WHERE v.service_group_id = ? ORDER BY v.display_order, v.token_prefix, v.id", (rs, i) -> service(rs), groupId);
    }

    Optional<ServiceEntry> service(UUID id) {
        return jdbc.query(SERVICE + " WHERE v.id = ?", (rs, i) -> service(rs), id).stream().findFirst();
    }

    void insert(ServiceEntry service) {
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, icon, display_order,"
                        + " requires_visitor_id, booking_mode, parallel_serving, parallel_limit, active, created_at, updated_at) VALUES (?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                service.id(), service.serviceGroupId(), json(service.nameI18n()), service.tokenPrefix(), service.expectedMinutes(), service.slaWaitMinutes(),
                json(service.channels()), service.icon(), service.displayOrder(), service.visitorIdentifier(), service.bookingMode(), service.parallelServing(),
                service.parallelLimit(), service.active(),
                ts(service.createdAt()), ts(service.updatedAt()));
    }

    void update(ServiceEntry service) {
        jdbc.update(
                "UPDATE service SET name_i18n = ?::jsonb, token_prefix = ?, expected_minutes = ?, sla_wait_minutes = ?, channels = ?::jsonb, icon = ?,"
                        + " display_order = ?, requires_visitor_id = ?, booking_mode = ?, parallel_serving = ?, parallel_limit = ?, updated_at = ? WHERE id = ?",
                json(service.nameI18n()), service.tokenPrefix(), service.expectedMinutes(), service.slaWaitMinutes(), json(service.channels()), service.icon(),
                service.displayOrder(), service.visitorIdentifier(), service.bookingMode(), service.parallelServing(), service.parallelLimit(), ts(service.updatedAt()),
                service.id());
    }

    void setServiceActive(UUID id, boolean active, Instant now) {
        jdbc.update("UPDATE service SET active = ?, updated_at = ? WHERE id = ?", active, ts(now), id);
    }

    List<UUID> activeServiceIdsOfGroup(UUID groupId) {
        return jdbc.queryForList("SELECT id FROM service WHERE service_group_id = ? AND active ORDER BY id", UUID.class, groupId);
    }

    /** Removes the service row itself; its links and outcome codes must already be gone. */
    void deleteService(UUID id) {
        jdbc.update("DELETE FROM service WHERE id = ?", id);
    }

    // ---- counter links ------------------------------------------------------------------------------------------

    List<CounterLink> links(UUID serviceId) {
        return jdbc.query(
                "SELECT cs.counter_id, cs.service_id, cs.preference_weight, c.label, c.active FROM counter_service cs JOIN counter c ON c.id = cs.counter_id"
                        + " WHERE cs.service_id = ? ORDER BY cs.preference_weight, lower(c.label), c.id",
                (rs, i) -> new CounterLink(
                        rs.getObject("counter_id", UUID.class), rs.getObject("service_id", UUID.class), rs.getInt("preference_weight"),
                        rs.getString("label"), rs.getBoolean("active")),
                serviceId);
    }

    Optional<CounterLink> link(UUID serviceId, UUID counterId) {
        return links(serviceId).stream().filter(l -> l.counterId().equals(counterId)).findFirst();
    }

    void upsertLink(UUID counterId, UUID serviceId, int weight) {
        jdbc.update(
                "INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, ?)"
                        + " ON CONFLICT (counter_id, service_id) DO UPDATE SET preference_weight = EXCLUDED.preference_weight",
                counterId, serviceId, weight);
    }

    void deleteLink(UUID counterId, UUID serviceId) {
        jdbc.update("DELETE FROM counter_service WHERE counter_id = ? AND service_id = ?", counterId, serviceId);
    }

    void deleteLinksOfService(UUID serviceId) {
        jdbc.update("DELETE FROM counter_service WHERE service_id = ?", serviceId);
    }

    // ---- outcome codes ------------------------------------------------------------------------------------------

    List<OutcomeCode> outcomesOfService(UUID serviceId) {
        return jdbc.query(OUTCOME + " WHERE o.service_id = ? ORDER BY o.display_order, o.code, o.id", (rs, i) -> outcome(rs), serviceId);
    }

    Optional<OutcomeCode> outcome(UUID id) {
        return jdbc.query(OUTCOME + " WHERE o.id = ?", (rs, i) -> outcome(rs), id).stream().findFirst();
    }

    void insert(OutcomeCode outcome) {
        jdbc.update(
                "INSERT INTO outcome_code (id, service_id, code, label_i18n, display_order, active, created_at, updated_at) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                outcome.id(), outcome.serviceId(), outcome.code(), json(outcome.labelI18n()), outcome.displayOrder(), outcome.active(),
                ts(outcome.createdAt()), ts(outcome.updatedAt()));
    }

    void update(OutcomeCode outcome) {
        jdbc.update("UPDATE outcome_code SET label_i18n = ?::jsonb, display_order = ?, updated_at = ? WHERE id = ?",
                json(outcome.labelI18n()), outcome.displayOrder(), ts(outcome.updatedAt()), outcome.id());
    }

    void setOutcomeActive(UUID id, boolean active, Instant now) {
        jdbc.update("UPDATE outcome_code SET active = ?, updated_at = ? WHERE id = ?", active, ts(now), id);
    }

    void deleteOutcomesOfService(UUID serviceId) {
        jdbc.update("DELETE FROM outcome_code WHERE service_id = ?", serviceId);
    }

    // ---- mapping ------------------------------------------------------------------------------------------------

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private List<String> strings(String json) {
        return List.copyOf(Arrays.asList(mapper.readValue(json, String[].class)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> names(String json) {
        return new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private ServiceGroup group(ResultSet rs) throws SQLException {
        Map<String, String> names = names(rs.getString("name_i18n"));
        return new ServiceGroup(
                rs.getObject("id", UUID.class),
                rs.getObject("site_id", UUID.class),
                names,
                CatalogueRules.missing(names, strings(rs.getString("enabled_languages"))),
                rs.getString("token_prefix"),
                rs.getInt("display_order"),
                rs.getBoolean("active"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private ServiceEntry service(ResultSet rs) throws SQLException {
        Map<String, String> names = names(rs.getString("name_i18n"));
        return new ServiceEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("service_group_id", UUID.class),
                rs.getObject("site_id", UUID.class),
                names,
                CatalogueRules.missing(names, strings(rs.getString("enabled_languages"))),
                rs.getString("token_prefix"),
                rs.getInt("expected_minutes"),
                rs.getInt("sla_wait_minutes"),
                strings(rs.getString("channels")),
                rs.getString("icon"),
                rs.getInt("display_order"),
                rs.getString("requires_visitor_id"),
                rs.getString("booking_mode"),
                rs.getBoolean("parallel_serving"),
                rs.getInt("parallel_limit"),
                rs.getBoolean("active"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private OutcomeCode outcome(ResultSet rs) throws SQLException {
        Map<String, String> labels = names(rs.getString("label_i18n"));
        return new OutcomeCode(
                rs.getObject("id", UUID.class),
                rs.getObject("service_id", UUID.class),
                rs.getObject("site_id", UUID.class),
                rs.getString("code"),
                labels,
                CatalogueRules.missing(labels, strings(rs.getString("enabled_languages"))),
                rs.getInt("display_order"),
                rs.getBoolean("active"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }
}
