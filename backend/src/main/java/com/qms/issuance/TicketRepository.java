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
            String groupPrefix,
            List<String> channels,
            String bookingMode,
            boolean active,
            String timezone,
            String visitorIdentifier) {}

    /**
     * A new ticket row. {@code purposeNote} is the agent-visible note Reception adds at issuance (FR-ISS-020).
     * {@code targetAgentId} and {@code customLevelId} are the kiosk selection tree's individual and custom levels
     * (ticket 26, FR-ISS-010..012); both are null when the visitor's path never reached, or skipped, that level.
     * {@code queuedAt} is when the ticket's real wait starts (FR-QUE-020): {@code issuedAt} for every channel but a
     * checked-in appointment, where it is the later of the slot time and the check-in (ticket 35).
     * {@code appointmentBonusMinutes} is the fixed bonus a checked-in appointment's ticket carries in its score
     * (FR-QUE-020, FR-APT-032); 0 for every other channel. {@code initialState} is {@code waiting} for every channel but a
     * remote join (ticket 42, FR-MOB-010), which starts a ticket {@code remote} instead — queued and accruing wait exactly
     * like a waiting ticket, but never callable until it is checked in (ticket 43).
     */
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
            Instant queuedAt,
            String secretHash,
            UUID priorityClassId,
            UUID visitorId,
            String purposeNote,
            UUID targetAgentId,
            String customLevelId,
            int appointmentBonusMinutes,
            String initialState) {}

    /** A group joined to its site, for a kiosk scope check (ticket 26). */
    record GroupSite(UUID groupId, UUID siteId) {}

    /** An on-duty member of a group's team, with how many tickets already wait in their personal queue (FR-ISS-012). */
    record AgentQueueRow(UUID agentId, String displayName, int queueLength) {}

    /** A Priority class as issuance needs it: whether it can be given to a new ticket and the prefix it may impose. */
    record PriorityClassRef(UUID id, Map<String, String> names, boolean active, String prefixOverride) {}

    /** A ticket joined to the names it shows. {@code wayfindingImageUrl} is the ticket's own Zone's optional image (FR-MOB-032, ticket 37). */
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
            String wayfindingImageUrl,
            UUID visitId,
            String originChannel,
            Instant issuedAt,
            Instant queuedAt,
            int version,
            UUID priorityClassId,
            Map<String, String> priorityClassNames) {}

    private static final String TICKET =
            "SELECT t.id, t.token_number, t.state, t.service_id, v.name_i18n AS service_names, t.service_group_id, g.name_i18n AS group_names,"
                    + " t.site_id, t.zone_id, z.name AS zone_name, z.building_label, z.floor_label, z.wayfinding_image_url, t.visit_id, t.origin_channel, t.issued_at,"
                    + " t.queued_at, t.version, pc.id AS priority_class_id, pc.name_i18n AS priority_class_names"
                    + " FROM ticket t JOIN service v ON v.id = t.service_id JOIN service_group g ON g.id = t.service_group_id"
                    + " LEFT JOIN zone z ON z.id = t.zone_id"
                    // A ticket without a class of its own belongs to the default class.
                    + " LEFT JOIN priority_class pc ON pc.id = coalesce(t.priority_class_id, (SELECT id FROM priority_class WHERE is_default))";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    TicketRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---- what a ticket copies at issue ------------------------------------------------------------------------

    Optional<ServiceTarget> serviceTarget(UUID serviceId) {
        return jdbc.query(
                        "SELECT v.id, v.service_group_id, g.site_id, v.token_prefix, g.token_prefix AS group_prefix, v.channels, v.booking_mode,"
                                + " (v.active AND g.active AND s.active) AS active, s.timezone, v.requires_visitor_id"
                                + " FROM service v JOIN service_group g ON g.id = v.service_group_id JOIN site s ON s.id = g.site_id WHERE v.id = ?",
                        (rs, i) -> new ServiceTarget(
                                rs.getObject("id", UUID.class),
                                rs.getObject("service_group_id", UUID.class),
                                rs.getObject("site_id", UUID.class),
                                rs.getString("token_prefix"),
                                rs.getString("group_prefix"),
                                strings(rs.getString("channels")),
                                rs.getString("booking_mode"),
                                rs.getBoolean("active"),
                                rs.getString("timezone"),
                                rs.getString("requires_visitor_id")),
                        serviceId)
                .stream().findFirst();
    }

    // ---- kiosk selection tree (ticket 26, FR-ISS-010..014) ----------------------------------------------------

    Optional<GroupSite> groupSite(UUID groupId) {
        return jdbc.query(
                        "SELECT id, site_id FROM service_group WHERE id = ?",
                        (rs, i) -> new GroupSite(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class)),
                        groupId)
                .stream().findFirst();
    }

    /** Whether the group offers the individual level at all, and (when it does) the custom level's valid option ids. */
    record GroupSelection(boolean teamSelectable, boolean individualSelectable, List<String> customLevelOptionIds) {}

    Optional<GroupSelection> groupSelection(UUID groupId) {
        return jdbc.query(
                        "SELECT team_selectable, individual_selectable, custom_level_options FROM service_group WHERE id = ?",
                        (rs, i) -> new GroupSelection(
                                rs.getBoolean("team_selectable"), rs.getBoolean("individual_selectable"), customLevelOptionIds(rs.getString("custom_level_options"))),
                        groupId)
                .stream().findFirst();
    }

    /**
     * The "group queue" FR-ISS-012 compares an Agent's own queue against: tickets still waiting or paused in the
     * group that are not already earmarked for a specific Agent, i.e. the shared pool any team member could still
     * draw. A ticket already targeted at someone (this endpoint's own kind of ticket) is excluded, or an Agent's
     * personal queue — itself always a subset of the group's tickets — could never be found longer than it.
     */
    int groupQueueLength(UUID groupId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ticket WHERE service_group_id = ? AND state IN ('waiting', 'paused') AND target_agent_id IS NULL",
                Integer.class, groupId);
        return count == null ? 0 : count;
    }

    /** Active members of a group's team who are on duty right now: a live (open, on-break or closing) counter session. */
    List<AgentQueueRow> onDutyTeamAgents(UUID groupId) {
        return jdbc.query(
                "SELECT u.id, u.display_name,"
                        + " (SELECT count(*) FROM ticket t WHERE t.target_agent_id = u.id AND t.state IN ('waiting', 'paused')) AS queue_length"
                        + " FROM team tm JOIN team_member m ON m.team_id = tm.id JOIN users u ON u.id = m.user_id"
                        + " WHERE tm.service_group_id = ? AND u.active"
                        + " AND EXISTS (SELECT 1 FROM counter_session cs WHERE cs.agent_id = u.id AND cs.state IN ('open', 'on_break', 'closing'))"
                        + " ORDER BY u.display_name, u.id",
                (rs, i) -> new AgentQueueRow(rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getInt("queue_length")),
                groupId);
    }

    /** Whether {@code agentId} is an active member of {@code groupId}'s team and on duty right now (FR-ISS-012). */
    boolean agentOnDutyInGroup(UUID groupId, UUID agentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM team tm JOIN team_member m ON m.team_id = tm.id JOIN users u ON u.id = m.user_id"
                        + " JOIN counter_session cs ON cs.agent_id = u.id"
                        + " WHERE tm.service_group_id = ? AND m.user_id = ? AND u.active AND cs.state IN ('open', 'on_break', 'closing'))",
                Boolean.class, groupId, agentId));
    }

    private List<String> customLevelOptionIds(String json) {
        if (json == null) return List.of();
        Map<?, ?>[] rows = mapper.readValue(json, Map[].class);
        return java.util.Arrays.stream(rows).map(row -> String.valueOf(row.get("id"))).toList();
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

    Optional<PriorityClassRef> priorityClass(UUID id) {
        return jdbc.query(
                        "SELECT id, name_i18n, active, token_prefix_override FROM priority_class WHERE id = ?",
                        (rs, i) -> new PriorityClassRef(rs.getObject("id", UUID.class), names(rs.getString("name_i18n")), rs.getBoolean("active"), rs.getString("token_prefix_override")),
                        id)
                .stream().findFirst();
    }

    /** The class the tickets of a channel get by default; empty when none is set or the class has been switched off (FR-QUE-011). */
    Optional<UUID> channelDefaultClass(String channel) {
        return jdbc.query(
                        "SELECT c.id FROM channel_priority_default d JOIN priority_class c ON c.id = d.priority_class_id WHERE d.channel = ? AND c.active",
                        (rs, i) -> rs.getObject("id", UUID.class),
                        channel)
                .stream().findFirst();
    }

    /** The class the tickets of a service get by default; empty when none is set or the class has been switched off (FR-QUE-011). */
    Optional<UUID> serviceDefaultClass(UUID serviceId) {
        return jdbc.query(
                        "SELECT c.id FROM service v JOIN priority_class c ON c.id = v.default_priority_class_id WHERE v.id = ? AND c.active",
                        (rs, i) -> rs.getObject("id", UUID.class),
                        serviceId)
                .stream().findFirst();
    }

    // ---- writes -----------------------------------------------------------------------------------------------

    void insertVisit(UUID id, UUID siteId, Instant startedAt) {
        jdbc.update("INSERT INTO visit (id, site_id, started_at) VALUES (?, ?, ?)", id, siteId, ts(startedAt));
    }

    void insertTicket(NewTicket t) {
        jdbc.update(
                "INSERT INTO ticket (id, token_number, sequence_no, reset_key, service_id, service_group_id, site_id, zone_id, visit_id,"
                        + " origin_channel, state, issued_at, queued_at, secret_hash, priority_class_id, visitor_id, purpose_note, target_agent_id, custom_level_id,"
                        + " appointment_bonus_minutes)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                t.id(), t.tokenNumber(), t.sequenceNo(), t.resetKey(), t.target().serviceId(), t.target().groupId(), t.target().siteId(), t.zoneId(),
                t.visitId(), t.originChannel(), t.initialState(), ts(t.issuedAt()), ts(t.queuedAt()), t.secretHash(), t.priorityClassId(), t.visitorId(),
                t.purposeNote(), t.targetAgentId(), t.customLevelId(), t.appointmentBonusMinutes());
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
                        rs.getString("wayfinding_image_url"),
                        rs.getObject("visit_id", UUID.class),
                        rs.getString("origin_channel"),
                        rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("queued_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("version"),
                        rs.getObject("priority_class_id", UUID.class),
                        rs.getString("priority_class_names") == null ? Map.of() : names(rs.getString("priority_class_names"))),
                id)
                .stream().findFirst();
    }

    // ---- visitor ticket page (ticket 37, §20.2, FR-SEC-033, FR-MOB-013, FR-MOB-030) ------------------------------

    /** The stored hash of a ticket's secret, to check what a visitor presents against (§18.3); empty when the ticket does not exist. */
    Optional<String> secretHash(UUID id) {
        return jdbc.query("SELECT secret_hash FROM ticket WHERE id = ?", (rs, i) -> rs.getString("secret_hash"), id).stream().findFirst();
    }

    /**
     * The token most recently called into service for a Service, for "the token now being served" a visitor is shown
     * (FR-MOB-013): the ticket whose most recent {@code serving} transition is the latest of any ticket still in that
     * state. Several counters may serve the same Service at once; this names only the last one to start.
     */
    Optional<String> nowServingToken(UUID serviceId) {
        return jdbc.query(
                        "SELECT t.token_number FROM ticket t"
                                + " JOIN ticket_event e ON e.ticket_id = t.id AND e.to_state = 'serving'"
                                + " WHERE t.service_id = ? AND t.state = 'serving'"
                                + " ORDER BY e.occurred_at DESC, e.seq DESC LIMIT 1",
                        (rs, i) -> rs.getString("token_number"),
                        serviceId)
                .stream().findFirst();
    }

    /**
     * A visitor's own cancel (FR-MOB-030): closes the ticket as {@code cancelled} only while it is still in one of the
     * given states, the same guarded conditional update every other action here uses instead of a separate lock read.
     * Returns whether it changed a row; false means the ticket had already moved on (called, or cancelled twice).
     */
    boolean cancelIfIn(UUID id, List<String> states) {
        String placeholders = states.stream().map(s -> "?").collect(java.util.stream.Collectors.joining(","));
        Object[] args = new Object[states.size() + 1];
        args[0] = id;
        for (int i = 0; i < states.size(); i++) args[i + 1] = states.get(i);
        int updated = jdbc.update("UPDATE ticket SET state = 'cancelled', version = version + 1 WHERE id = ? AND state IN (" + placeholders + ")", args);
        return updated == 1;
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
