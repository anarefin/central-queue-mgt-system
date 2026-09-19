package com.qms.issuance;

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
 * SQL for visits and tickets. The catalogue and site tables are read here for what issuance must copy or check at issue
 * (SRS §18.5); nothing in this class writes them, and issuance does not depend on the configuration package.
 */
@Repository
class TicketRepository {

    /** What issuance needs to know about the service a ticket is for, resolved up to its group and site. */
    record ServiceTarget(
            UUID serviceId,
            UUID groupId,
            UUID siteId,
            String tokenPrefix,
            List<String> channels,
            String bookingMode,
            boolean active,
            String timezone) {}

    /** A new ticket row. */
    record NewTicket(
            UUID id,
            String tokenNumber,
            long sequenceNo,
            String resetKey,
            ServiceTarget target,
            UUID zoneId,
            UUID visitId,
            String originChannel,
            Instant issuedAt,
            String secretHash) {}

    /** A ticket joined to the names it shows. */
    record TicketRecord(
            UUID id,
            String tokenNumber,
            String state,
            UUID serviceId,
            Map<String, String> serviceNames,
            UUID groupId,
            Map<String, String> groupNames,
            UUID siteId,
            UUID zoneId,
            String zoneName,
            String buildingLabel,
            String floorLabel,
            UUID visitId,
            String originChannel,
            Instant issuedAt,
            Instant queuedAt,
            int version) {}

    private static final String TICKET =
            "SELECT t.id, t.token_number, t.state, t.service_id, v.name_i18n AS service_names, t.service_group_id, g.name_i18n AS group_names,"
                    + " t.site_id, t.zone_id, z.name AS zone_name, z.building_label, z.floor_label, t.visit_id, t.origin_channel, t.issued_at,"
                    + " t.queued_at, t.version"
                    + " FROM ticket t JOIN service v ON v.id = t.service_id JOIN service_group g ON g.id = t.service_group_id"
                    + " LEFT JOIN zone z ON z.id = t.zone_id";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    TicketRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---- what a ticket copies at issue ------------------------------------------------------------------------

    Optional<ServiceTarget> serviceTarget(UUID serviceId) {
        return jdbc.query(
                        "SELECT v.id, v.service_group_id, g.site_id, v.token_prefix, v.channels, v.booking_mode,"
                                + " (v.active AND g.active AND s.active) AS active, s.timezone"
                                + " FROM service v JOIN service_group g ON g.id = v.service_group_id JOIN site s ON s.id = g.site_id WHERE v.id = ?",
                        (rs, i) -> new ServiceTarget(
                                rs.getObject("id", UUID.class),
                                rs.getObject("service_group_id", UUID.class),
                                rs.getObject("site_id", UUID.class),
                                rs.getString("token_prefix"),
                                strings(rs.getString("channels")),
                                rs.getString("booking_mode"),
                                rs.getBoolean("active"),
                                rs.getString("timezone")),
                        serviceId)
                .stream().findFirst();
    }

    /**
     * The zone a ticket for this service waits in: that of the service's primary active counter (lowest preference
     * weight), or null when no active counter serves it yet.
     */
    UUID waitingZone(UUID serviceId) {
        return jdbc.query(
                        "SELECT z.id FROM counter_service cs JOIN counter c ON c.id = cs.counter_id JOIN zone z ON z.id = c.zone_id"
                                + " WHERE cs.service_id = ? AND c.active AND z.active ORDER BY cs.preference_weight, z.display_order, z.id LIMIT 1",
                        (rs, i) -> rs.getObject("id", UUID.class),
                        serviceId)
                .stream().findFirst().orElse(null);
    }

    // ---- writes -----------------------------------------------------------------------------------------------

    void insertVisit(UUID id, UUID siteId, Instant startedAt) {
        jdbc.update("INSERT INTO visit (id, site_id, started_at) VALUES (?, ?, ?)", id, siteId, ts(startedAt));
    }

    void insertTicket(NewTicket t) {
        jdbc.update(
                "INSERT INTO ticket (id, token_number, sequence_no, reset_key, service_id, service_group_id, site_id, zone_id, visit_id,"
                        + " origin_channel, state, issued_at, queued_at, secret_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'waiting', ?, ?, ?)",
                t.id(), t.tokenNumber(), t.sequenceNo(), t.resetKey(), t.target().serviceId(), t.target().groupId(), t.target().siteId(), t.zoneId(),
                t.visitId(), t.originChannel(), ts(t.issuedAt()), ts(t.issuedAt()), t.secretHash());
    }

    // ---- reads ------------------------------------------------------------------------------------------------

    Optional<TicketRecord> ticket(UUID id) {
        return jdbc.query(TICKET + " WHERE t.id = ?", (rs, i) -> new TicketRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("token_number"),
                        rs.getString("state"),
                        rs.getObject("service_id", UUID.class),
                        names(rs.getString("service_names")),
                        rs.getObject("service_group_id", UUID.class),
                        names(rs.getString("group_names")),
                        rs.getObject("site_id", UUID.class),
                        rs.getObject("zone_id", UUID.class),
                        rs.getString("zone_name"),
                        rs.getString("building_label"),
                        rs.getString("floor_label"),
                        rs.getObject("visit_id", UUID.class),
                        rs.getString("origin_channel"),
                        rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("queued_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("version")),
                id)
                .stream().findFirst();
    }

    record SiteInfo(UUID id, String defaultLanguage) {}

    Optional<SiteInfo> site(UUID siteId) {
        return jdbc.query("SELECT id, default_language FROM site WHERE id = ?", (rs, i) -> new SiteInfo(rs.getObject("id", UUID.class), rs.getString("default_language")), siteId)
                .stream().findFirst();
    }

    /** Names and group of a service, for a queue snapshot; the site comes from the group. */
    record ServiceNames(UUID serviceId, Map<String, String> names, UUID groupId, UUID siteId) {}

    Optional<ServiceNames> serviceNames(UUID serviceId) {
        return jdbc.query(
                        "SELECT v.id, v.name_i18n, v.service_group_id, g.site_id FROM service v JOIN service_group g ON g.id = v.service_group_id WHERE v.id = ?",
                        (rs, i) -> new ServiceNames(rs.getObject("id", UUID.class), names(rs.getString("name_i18n")), rs.getObject("service_group_id", UUID.class), rs.getObject("site_id", UUID.class)),
                        serviceId)
                .stream().findFirst();
    }

    /** An active service a site offers, with its queue length. */
    record OfferedService(
            UUID id,
            Map<String, String> names,
            UUID groupId,
            Map<String, String> groupNames,
            String tokenPrefix,
            String icon,
            int displayOrder,
            int waitingCount) {}

    /**
     * Active services of active groups of one site, in display order, optionally only those that issue on a channel. A
     * service that takes appointments only is left out of walk-in channels (FR-CFG-014).
     */
    List<OfferedService> offeredServices(UUID siteId, String channel) {
        String filter = channel == null
                ? ""
                : " AND v.channels @> ?::jsonb" + (Channels.APPOINTMENT_CHECKIN.equals(channel) ? "" : " AND v.booking_mode <> 'appointment_only'");
        Object[] arguments = channel == null ? new Object[] {siteId} : new Object[] {siteId, "[\"" + channel + "\"]"};
        return jdbc.query(
                "SELECT v.id, v.name_i18n, g.id AS group_id, g.name_i18n AS group_names, v.token_prefix, v.icon, v.display_order,"
                        + " (SELECT count(*) FROM ticket t WHERE t.service_id = v.id AND t.state IN ('waiting', 'paused')) AS waiting"
                        + " FROM service v JOIN service_group g ON g.id = v.service_group_id"
                        + " WHERE g.site_id = ? AND v.active AND g.active" + filter
                        + " ORDER BY g.display_order, g.token_prefix, v.display_order, v.token_prefix, v.id",
                (rs, i) -> new OfferedService(
                        rs.getObject("id", UUID.class),
                        names(rs.getString("name_i18n")),
                        rs.getObject("group_id", UUID.class),
                        names(rs.getString("group_names")),
                        rs.getString("token_prefix"),
                        rs.getString("icon"),
                        rs.getInt("display_order"),
                        rs.getInt("waiting")),
                arguments);
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
}
