package com.qms.appointment;

import com.qms.appointment.AppointmentRows.DateException;
import com.qms.appointment.AppointmentRows.ServiceSettings;
import com.qms.appointment.AppointmentRows.Template;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL for appointment availability: the slot templates and exceptions at each level, a Service's booking-horizon
 * settings, and what a search needs of the Service's team, its Site and that Site's business hours and holidays. Like
 * issuance (ticket 21) it reads catalogue, team and business-hours tables directly and writes only its own.
 */
@Repository
class AppointmentAvailabilityRepository {

    /** The Site behind a Service, its time zone, and whether the Service takes appointments at all. */
    record SiteContext(UUID siteId, String timezone, String bookingMode) {}

    private static final RowMapper<Template> TEMPLATE_ROW =
            (rs, i) -> new Template(
                    rs.getObject("id", UUID.class),
                    AppointmentLevel.fromWire(rs.getString("level")),
                    rs.getObject("target_id", UUID.class),
                    rs.getInt("weekday"),
                    rs.getObject("start_time", LocalTime.class),
                    rs.getObject("end_time", LocalTime.class),
                    rs.getInt("slot_minutes"),
                    rs.getInt("capacity"),
                    rs.getObject("valid_from", LocalDate.class),
                    rs.getObject("valid_to", LocalDate.class));

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    AppointmentAvailabilityRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---- what a rule can be set for ---------------------------------------------------------------------------

    Optional<SiteContext> siteContextOfService(UUID serviceId) {
        return jdbc.query(
                        "SELECT sg.site_id, st.timezone, sv.booking_mode FROM service sv"
                                + " JOIN service_group sg ON sg.id = sv.service_group_id JOIN site st ON st.id = sg.site_id WHERE sv.id = ?",
                        (rs, i) -> new SiteContext(rs.getObject("site_id", UUID.class), rs.getString("timezone"), rs.getString("booking_mode")),
                        serviceId)
                .stream().findFirst();
    }

    /** The one Team of a Service's group (CONTEXT.md: exactly one team per group), if the group has one yet. */
    Optional<UUID> teamIdOfService(UUID serviceId) {
        return jdbc.query(
                        "SELECT t.id FROM service sv JOIN team t ON t.service_group_id = sv.service_group_id WHERE sv.id = ?",
                        (rs, i) -> rs.getObject("id", UUID.class),
                        serviceId)
                .stream().findFirst();
    }

    Optional<UUID> siteOfTeam(UUID teamId) {
        return jdbc.query(
                        "SELECT sg.site_id FROM team t JOIN service_group sg ON sg.id = t.service_group_id WHERE t.id = ?",
                        (rs, i) -> rs.getObject("site_id", UUID.class),
                        teamId)
                .stream().findFirst();
    }

    boolean userExists(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)", Boolean.class, userId));
    }

    /** Every Site an Agent's teams put them in scope of, so a scoped admin's site claim can be checked against it. */
    Set<UUID> sitesOfAgent(UUID userId) {
        return Set.copyOf(jdbc.query(
                "SELECT DISTINCT sg.site_id FROM team_member tm JOIN team t ON t.id = tm.team_id JOIN service_group sg ON sg.id = t.service_group_id WHERE tm.user_id = ?",
                (rs, i) -> rs.getObject("site_id", UUID.class),
                userId));
    }

    // ---- slot templates (FR-APT-002) ---------------------------------------------------------------------------

    List<Template> templates(AppointmentLevel level, UUID targetId) {
        return jdbc.query(
                "SELECT id, level, target_id, weekday, start_time, end_time, slot_minutes, capacity, valid_from, valid_to"
                        + " FROM appointment_slot_template WHERE level = ? AND target_id = ? ORDER BY weekday, start_time",
                TEMPLATE_ROW,
                level.wire(), targetId);
    }

    void replaceTemplates(AppointmentLevel level, UUID targetId, List<Template> rows) {
        jdbc.update("DELETE FROM appointment_slot_template WHERE level = ? AND target_id = ?", level.wire(), targetId);
        for (Template row : rows) {
            jdbc.update(
                    "INSERT INTO appointment_slot_template (id, level, target_id, weekday, start_time, end_time, slot_minutes, capacity, valid_from, valid_to)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    row.id(), level.wire(), targetId, row.weekday(), row.start(), row.end(), row.slotMinutes(), row.capacity(), row.validFrom(), row.validTo());
        }
    }

    // ---- exceptions (FR-APT-003, FR-APT-004) -------------------------------------------------------------------

    List<DateException> exceptions(AppointmentLevel level, UUID targetId) {
        return jdbc.query(
                "SELECT id, level, target_id, exception_date, exception_type, start_time, end_time, slot_minutes, capacity, note_i18n::text AS note_i18n"
                        + " FROM appointment_exception WHERE level = ? AND target_id = ? ORDER BY exception_date",
                exceptionRow(),
                level.wire(), targetId);
    }

    Optional<DateException> exception(UUID id) {
        return jdbc.query(
                        "SELECT id, level, target_id, exception_date, exception_type, start_time, end_time, slot_minutes, capacity, note_i18n::text AS note_i18n"
                                + " FROM appointment_exception WHERE id = ?",
                        exceptionRow(),
                        id)
                .stream().findFirst();
    }

    Optional<DateException> exceptionOn(AppointmentLevel level, UUID targetId, LocalDate date) {
        return jdbc.query(
                        "SELECT id, level, target_id, exception_date, exception_type, start_time, end_time, slot_minutes, capacity, note_i18n::text AS note_i18n"
                                + " FROM appointment_exception WHERE level = ? AND target_id = ? AND exception_date = ?",
                        exceptionRow(),
                        level.wire(), targetId, date)
                .stream().findFirst();
    }

    void insertException(DateException row) {
        jdbc.update(
                "INSERT INTO appointment_exception (id, level, target_id, exception_date, exception_type, start_time, end_time, slot_minutes, capacity, note_i18n)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                row.id(), row.level().wire(), row.targetId(), row.date(), row.type(), row.start(), row.end(), row.slotMinutes(), row.capacity(), json(row.noteI18n()));
    }

    void deleteException(UUID id) {
        jdbc.update("DELETE FROM appointment_exception WHERE id = ?", id);
    }

    private RowMapper<DateException> exceptionRow() {
        return (rs, i) -> new DateException(
                rs.getObject("id", UUID.class),
                AppointmentLevel.fromWire(rs.getString("level")),
                rs.getObject("target_id", UUID.class),
                rs.getObject("exception_date", LocalDate.class),
                rs.getString("exception_type"),
                rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class),
                rs.getObject("slot_minutes", Integer.class),
                rs.getObject("capacity", Integer.class),
                names(rs.getString("note_i18n")));
    }

    // ---- a Service's booking horizon and minimum lead time (FR-APT-005) ---------------------------------------

    Optional<ServiceSettings> settings(UUID serviceId) {
        return jdbc.query(
                        "SELECT booking_horizon_days, min_lead_time_minutes, waitlist_enabled FROM appointment_service_settings WHERE service_id = ?",
                        (rs, i) -> new ServiceSettings(rs.getInt("booking_horizon_days"), rs.getInt("min_lead_time_minutes"), rs.getBoolean("waitlist_enabled")),
                        serviceId)
                .stream().findFirst();
    }

    void saveSettings(UUID serviceId, ServiceSettings settings, Instant now) {
        jdbc.update(
                "INSERT INTO appointment_service_settings (service_id, booking_horizon_days, min_lead_time_minutes, waitlist_enabled, updated_at) VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT (service_id) DO UPDATE SET booking_horizon_days = EXCLUDED.booking_horizon_days,"
                        + " min_lead_time_minutes = EXCLUDED.min_lead_time_minutes, waitlist_enabled = EXCLUDED.waitlist_enabled, updated_at = EXCLUDED.updated_at",
                serviceId, settings.bookingHorizonDays(), settings.minLeadTimeMinutes(), settings.waitlistEnabled(), ts(now));
    }

    // ---- business hours and holidays, read only (FR-APT-004) --------------------------------------------------

    /** The hours in force for a Service: its own week when it has one, else its Site's. Empty means unrestricted. */
    Map<DayOfWeek, BusinessWindow.Day> week(UUID siteId, UUID serviceId) {
        List<Object[]> rows = jdbc.query(
                "SELECT weekday, open_time, close_time FROM business_hours WHERE scope_type = 'service' AND scope_id = ? ORDER BY weekday",
                (rs, i) -> new Object[] {rs.getInt("weekday"), rs.getObject("open_time", LocalTime.class), rs.getObject("close_time", LocalTime.class)},
                serviceId);
        if (rows.isEmpty()) {
            rows = jdbc.query(
                    "SELECT weekday, open_time, close_time FROM business_hours WHERE scope_type = 'site' AND scope_id = ? ORDER BY weekday",
                    (rs, i) -> new Object[] {rs.getInt("weekday"), rs.getObject("open_time", LocalTime.class), rs.getObject("close_time", LocalTime.class)},
                    siteId);
        }
        Map<DayOfWeek, BusinessWindow.Day> week = new EnumMap<>(DayOfWeek.class);
        for (Object[] row : rows) {
            week.put(DayOfWeek.of((Integer) row[0]), new BusinessWindow.Day((LocalTime) row[1], (LocalTime) row[2]));
        }
        return week;
    }

    Optional<BusinessWindow.Holiday> holidayOn(UUID siteId, LocalDate date) {
        return jdbc.query(
                        "SELECT half_day, close_time FROM holiday WHERE site_id = ? AND holiday_date = ?",
                        (rs, i) -> new BusinessWindow.Holiday(rs.getBoolean("half_day"), rs.getObject("close_time", LocalTime.class)),
                        siteId, date)
                .stream().findFirst();
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
