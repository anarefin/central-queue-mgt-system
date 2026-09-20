package com.qms.issuance;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL for the issuance rules: hours, holidays, cut-offs, per-Service caps and duplicate policy, the deployment-wide
 * settings, and the counts issuance checks them against. Like the rest of issuance it reads the catalogue and ticket
 * tables directly and writes only its own.
 */
@Repository
class IssuanceRulesRepository {

    static final String SITE = "site";
    static final String SERVICE = "service";

    /** One weekday's hours; {@code weekday} is ISO, 1 Monday to 7 Sunday. */
    record HoursRow(int weekday, LocalTime open, LocalTime close) {}

    record HolidayRow(UUID id, UUID siteId, LocalDate date, String name, boolean halfDay, LocalTime closeTime) {}

    /** What a Service adds to its issuance: the daily cap (null for none), its message and the duplicate and roster rules. */
    record ServiceRule(Integer dailyCap, Map<String, String> capMessage, String duplicatePolicy, boolean requireAgent) {
        static final ServiceRule NONE = new ServiceRule(null, Map.of(), "allow", false);
    }

    record Settings(boolean maintenanceEnabled, Map<String, String> maintenanceMessage, int deviceLimitPerMinute, int visitorLimitPerHour) {}

    /** A Service's remote-join policy (ticket 42, FR-MOB-011). No row means the virtual-queue flag is off. */
    record RemoteRule(boolean virtualQueueEnabled, Integer maxDistanceMeters, int maxRemoteSharePct, int joinWindowMinutes, int arrivalDeadlineMinutes) {
        static final RemoteRule NONE = new RemoteRule(false, null, 40, 30, 15);
    }

    /** A Site's own coordinates (ticket 42, FR-MOB-011), for the max-distance leg of a remote join. */
    record SiteLocation(double latitude, double longitude) {}

    private static final RowMapper<HolidayRow> HOLIDAYS = (rs, i) -> new HolidayRow(
            rs.getObject("id", UUID.class),
            rs.getObject("site_id", UUID.class),
            rs.getObject("holiday_date", LocalDate.class),
            rs.getString("name"),
            rs.getBoolean("half_day"),
            rs.getObject("close_time", LocalTime.class));

    private static final String HOLIDAY = "SELECT id, site_id, holiday_date, name, half_day, close_time FROM holiday";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    IssuanceRulesRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---- what a rule can be set for ---------------------------------------------------------------------------

    boolean siteExists(UUID siteId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM site WHERE id = ?)", Boolean.class, siteId));
    }

    /** The Site a Service belongs to, through its group. */
    Optional<UUID> siteOfService(UUID serviceId) {
        return jdbc.query(
                        "SELECT g.site_id FROM service v JOIN service_group g ON g.id = v.service_group_id WHERE v.id = ?",
                        (rs, i) -> rs.getObject("site_id", UUID.class),
                        serviceId)
                .stream().findFirst();
    }

    // ---- hours ------------------------------------------------------------------------------------------------

    List<HoursRow> hours(String scopeType, UUID scopeId) {
        return jdbc.query(
                "SELECT weekday, open_time, close_time FROM business_hours WHERE scope_type = ? AND scope_id = ? ORDER BY weekday",
                (rs, i) -> new HoursRow(rs.getInt("weekday"), rs.getObject("open_time", LocalTime.class), rs.getObject("close_time", LocalTime.class)),
                scopeType, scopeId);
    }

    void replaceHours(String scopeType, UUID scopeId, List<HoursRow> rows) {
        jdbc.update("DELETE FROM business_hours WHERE scope_type = ? AND scope_id = ?", scopeType, scopeId);
        for (HoursRow row : rows) {
            jdbc.update(
                    "INSERT INTO business_hours (id, scope_type, scope_id, weekday, open_time, close_time) VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), scopeType, scopeId, row.weekday(), row.open(), row.close());
        }
    }

    /** The hours in force for a Service: its own week when it has one, else its Site's. Empty when neither has any. */
    Map<DayOfWeek, OpeningHours.Day> week(UUID siteId, UUID serviceId) {
        List<HoursRow> rows = hours(SERVICE, serviceId);
        if (rows.isEmpty()) rows = hours(SITE, siteId);
        Map<DayOfWeek, OpeningHours.Day> week = new EnumMap<>(DayOfWeek.class);
        for (HoursRow row : rows) week.put(DayOfWeek.of(row.weekday()), new OpeningHours.Day(row.open(), row.close()));
        return week;
    }

    // ---- holidays ---------------------------------------------------------------------------------------------

    List<HolidayRow> holidays(UUID siteId) {
        return jdbc.query(HOLIDAY + " WHERE site_id = ? ORDER BY holiday_date", HOLIDAYS, siteId);
    }

    Optional<HolidayRow> holiday(UUID id) {
        return jdbc.query(HOLIDAY + " WHERE id = ?", HOLIDAYS, id).stream().findFirst();
    }

    Optional<HolidayRow> holidayOn(UUID siteId, LocalDate date) {
        return jdbc.query(HOLIDAY + " WHERE site_id = ? AND holiday_date = ?", HOLIDAYS, siteId, date).stream().findFirst();
    }

    void insertHoliday(HolidayRow h) {
        jdbc.update(
                "INSERT INTO holiday (id, site_id, holiday_date, name, half_day, close_time) VALUES (?, ?, ?, ?, ?, ?)",
                h.id(), h.siteId(), h.date(), h.name(), h.halfDay(), h.closeTime());
    }

    void deleteHoliday(UUID id) {
        jdbc.update("DELETE FROM holiday WHERE id = ?", id);
    }

    // ---- cut-offs ---------------------------------------------------------------------------------------------

    Map<String, Integer> cutoffs(UUID siteId) {
        Map<String, Integer> minutes = new LinkedHashMap<>();
        jdbc.query(
                "SELECT channel, minutes_before_close FROM channel_cutoff WHERE site_id = ? ORDER BY channel",
                rs -> {
                    minutes.put(rs.getString("channel"), rs.getInt("minutes_before_close"));
                },
                siteId);
        return minutes;
    }

    void replaceCutoffs(UUID siteId, Map<String, Integer> minutes) {
        jdbc.update("DELETE FROM channel_cutoff WHERE site_id = ?", siteId);
        minutes.forEach((channel, value) -> jdbc.update("INSERT INTO channel_cutoff (site_id, channel, minutes_before_close) VALUES (?, ?, ?)", siteId, channel, value));
    }

    // ---- Service rules ----------------------------------------------------------------------------------------

    ServiceRule serviceRule(UUID serviceId) {
        return jdbc.query(
                        "SELECT daily_cap, cap_message_i18n, duplicate_policy, require_agent FROM service_issuance_rule WHERE service_id = ?",
                        (rs, i) -> new ServiceRule(
                                rs.getObject("daily_cap", Integer.class),
                                names(rs.getString("cap_message_i18n")),
                                rs.getString("duplicate_policy"),
                                rs.getBoolean("require_agent")),
                        serviceId)
                .stream().findFirst().orElse(ServiceRule.NONE);
    }

    void saveServiceRule(UUID serviceId, ServiceRule rule, Instant now) {
        jdbc.update(
                "INSERT INTO service_issuance_rule (service_id, daily_cap, cap_message_i18n, duplicate_policy, require_agent, updated_at)"
                        + " VALUES (?, ?, ?::jsonb, ?, ?, ?) ON CONFLICT (service_id) DO UPDATE SET daily_cap = EXCLUDED.daily_cap,"
                        + " cap_message_i18n = EXCLUDED.cap_message_i18n, duplicate_policy = EXCLUDED.duplicate_policy,"
                        + " require_agent = EXCLUDED.require_agent, updated_at = EXCLUDED.updated_at",
                serviceId, rule.dailyCap(), json(rule.capMessage()), rule.duplicatePolicy(), rule.requireAgent(), ts(now));
    }

    // ---- remote join (ticket 42, FR-MOB-010..012) --------------------------------------------------------------

    RemoteRule remoteRule(UUID serviceId) {
        return jdbc.query(
                        "SELECT virtual_queue_enabled, max_distance_m, max_remote_share_pct, join_window_minutes, arrival_deadline_minutes"
                                + " FROM service_remote_rule WHERE service_id = ?",
                        (rs, i) -> new RemoteRule(
                                rs.getBoolean("virtual_queue_enabled"),
                                rs.getObject("max_distance_m", Integer.class),
                                rs.getInt("max_remote_share_pct"),
                                rs.getInt("join_window_minutes"),
                                rs.getInt("arrival_deadline_minutes")),
                        serviceId)
                .stream().findFirst().orElse(RemoteRule.NONE);
    }

    void saveRemoteRule(UUID serviceId, RemoteRule rule, Instant now) {
        jdbc.update(
                "INSERT INTO service_remote_rule (service_id, virtual_queue_enabled, max_distance_m, max_remote_share_pct, join_window_minutes,"
                        + " arrival_deadline_minutes, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (service_id) DO UPDATE SET"
                        + " virtual_queue_enabled = EXCLUDED.virtual_queue_enabled, max_distance_m = EXCLUDED.max_distance_m,"
                        + " max_remote_share_pct = EXCLUDED.max_remote_share_pct, join_window_minutes = EXCLUDED.join_window_minutes,"
                        + " arrival_deadline_minutes = EXCLUDED.arrival_deadline_minutes, updated_at = EXCLUDED.updated_at",
                serviceId, rule.virtualQueueEnabled(), rule.maxDistanceMeters(), rule.maxRemoteSharePct(), rule.joinWindowMinutes(),
                rule.arrivalDeadlineMinutes(), ts(now));
    }

    Optional<SiteLocation> siteLocation(UUID siteId) {
        return jdbc.query(
                        "SELECT latitude, longitude FROM site_location WHERE site_id = ?",
                        (rs, i) -> new SiteLocation(rs.getDouble("latitude"), rs.getDouble("longitude")),
                        siteId)
                .stream().findFirst();
    }

    void saveSiteLocation(UUID siteId, SiteLocation location, Instant now) {
        jdbc.update(
                "INSERT INTO site_location (site_id, latitude, longitude, updated_at) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (site_id) DO UPDATE SET latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude, updated_at = EXCLUDED.updated_at",
                siteId, location.latitude(), location.longitude(), ts(now));
    }

    /** Tickets of a Service still in the queue: waiting, paused or remote (FR-MOB-011's "share of the queue"). */
    int activeQueueCount(UUID serviceId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ticket WHERE service_id = ? AND state IN ('waiting', 'paused', 'remote')", Integer.class, serviceId);
        return count == null ? 0 : count;
    }

    /** Of those, how many are still remote (not yet checked in). */
    int remoteCount(UUID serviceId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM ticket WHERE service_id = ? AND state = 'remote'", Integer.class, serviceId);
        return count == null ? 0 : count;
    }

    // ---- settings ---------------------------------------------------------------------------------------------

    Settings settings() {
        return jdbc.queryForObject(
                "SELECT maintenance_enabled, maintenance_message_i18n, device_limit_per_minute, visitor_limit_per_hour FROM issuance_settings WHERE id = 1",
                (rs, i) -> new Settings(
                        rs.getBoolean("maintenance_enabled"),
                        names(rs.getString("maintenance_message_i18n")),
                        rs.getInt("device_limit_per_minute"),
                        rs.getInt("visitor_limit_per_hour")));
    }

    void saveSettings(Settings settings, Instant now) {
        jdbc.update(
                "UPDATE issuance_settings SET maintenance_enabled = ?, maintenance_message_i18n = ?::jsonb, device_limit_per_minute = ?,"
                        + " visitor_limit_per_hour = ?, updated_at = ? WHERE id = 1",
                settings.maintenanceEnabled(), json(settings.maintenanceMessage()), settings.deviceLimitPerMinute(), settings.visitorLimitPerHour(), ts(now));
    }

    // ---- what issuance checks ---------------------------------------------------------------------------------

    /** The channel's cut-off in minutes before closing; 0 when the Site set none. */
    int cutoff(UUID siteId, String channel) {
        return jdbc.query(
                        "SELECT minutes_before_close FROM channel_cutoff WHERE site_id = ? AND channel = ?",
                        (rs, i) -> rs.getInt(1),
                        siteId, channel)
                .stream().findFirst().orElse(0);
    }

    /** New tickets of a Service in a period. A successor ticket made by a transfer is not a new issuance. */
    int issuedBetween(UUID serviceId, Instant from, Instant to) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ticket WHERE service_id = ? AND predecessor_ticket_id IS NULL AND issued_at >= ? AND issued_at < ?",
                Integer.class, serviceId, ts(from), ts(to));
        return count == null ? 0 : count;
    }

    /** Whether an active user is on the team of the Service's group. */
    boolean hasRosteredAgent(UUID serviceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM service v JOIN team t ON t.service_group_id = v.service_group_id"
                        + " JOIN team_member m ON m.team_id = t.id JOIN users u ON u.id = m.user_id WHERE v.id = ? AND u.active)",
                Boolean.class, serviceId));
    }

    boolean visitorExists(UUID visitorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM visitor WHERE id = ?)", Boolean.class, visitorId));
    }

    /** Whether the visitor has a ticket for the Service that has not yet left the queue or the counter. */
    boolean hasActiveTicket(UUID visitorId, UUID serviceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM ticket WHERE visitor_id = ? AND service_id = ?"
                        + " AND state IN ('remote', 'waiting', 'paused', 'called', 'serving', 'held'))",
                Boolean.class, visitorId, serviceId));
    }

    /** When the actor's tickets were issued since a moment, oldest first. */
    List<Instant> issuedBy(UUID actorId, String actorType, Instant since) {
        return jdbc.query(
                "SELECT recorded_at FROM ticket_event WHERE actor_id = ? AND actor_type = ? AND event_type = 'ticket.issued' AND recorded_at > ? ORDER BY recorded_at",
                (rs, i) -> rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                actorId, actorType, ts(since));
    }

    /** Serialises what is decided from a count until the deciding transaction ends, so two requests cannot both pass a limit. */
    void lock(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, key);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, String> names(String json) {
        return json == null ? Map.of() : new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }

    private String json(Map<String, String> names) {
        return names == null || names.isEmpty() ? null : mapper.writeValueAsString(names);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
