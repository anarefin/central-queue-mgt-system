package com.qms.mobile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** A registered visitor's own read model (ticket 41, FR-MOB-002): active tickets, appointment history, saved sites. */
@Repository
class VisitorDashboardRepository {

    /** Every non-terminal ticket state (FR-MOB-002): the fixed set of {@code ticket.state}'s own CHECK (V6) minus its
     * terminal outcomes ({@code completed}, {@code transferred}, {@code no_show}, {@code cancelled}, {@code forfeited}). */
    private static final String ACTIVE_TICKET_STATES_SQL = "('remote','waiting','paused','called','serving','held')";

    record TicketRow(UUID id, String tokenNumber, String state, UUID serviceId, Map<String, String> serviceNames, UUID siteId, String siteName, Instant issuedAt) {}

    record AppointmentRow(
            UUID id, String referenceCode, UUID serviceId, Map<String, String> serviceNames, UUID siteId, String siteName,
            LocalDate slotDate, LocalTime slotStart, LocalTime slotEnd, String state) {}

    record SavedSiteRow(UUID siteId, String siteName, Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();

    VisitorDashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<TicketRow> activeTickets(UUID visitorId) {
        return jdbc.query(
                "SELECT t.id, t.token_number, t.state, t.service_id, sv.name_i18n AS service_names, t.site_id, s.name AS site_name, t.issued_at "
                        + "FROM ticket t JOIN service sv ON sv.id = t.service_id JOIN site s ON s.id = t.site_id "
                        + "WHERE t.visitor_id = ? AND t.state IN " + ACTIVE_TICKET_STATES_SQL + " ORDER BY t.issued_at DESC",
                (rs, i) -> new TicketRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("token_number"),
                        rs.getString("state"),
                        rs.getObject("service_id", UUID.class),
                        names(rs.getString("service_names")),
                        rs.getObject("site_id", UUID.class),
                        rs.getString("site_name"),
                        rs.getObject("issued_at", OffsetDateTime.class).toInstant()),
                visitorId);
    }

    List<AppointmentRow> appointmentHistory(UUID visitorId) {
        return jdbc.query(
                "SELECT a.id, a.reference_code, a.service_id, sv.name_i18n AS service_names, sg.site_id, s.name AS site_name, "
                        + "a.slot_date, a.slot_start, a.slot_end, a.state "
                        + "FROM appointment a JOIN service sv ON sv.id = a.service_id JOIN service_group sg ON sg.id = sv.service_group_id "
                        + "JOIN site s ON s.id = sg.site_id "
                        + "WHERE a.visitor_id = ? AND a.state <> 'held_slot' ORDER BY a.slot_date DESC, a.slot_start DESC",
                (rs, i) -> new AppointmentRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("reference_code"),
                        rs.getObject("service_id", UUID.class),
                        names(rs.getString("service_names")),
                        rs.getObject("site_id", UUID.class),
                        rs.getString("site_name"),
                        rs.getObject("slot_date", LocalDate.class),
                        rs.getObject("slot_start", LocalTime.class),
                        rs.getObject("slot_end", LocalTime.class),
                        rs.getString("state")),
                visitorId);
    }

    List<SavedSiteRow> savedSites(UUID visitorId) {
        return jdbc.query(
                "SELECT vs.site_id, s.name AS site_name, vs.created_at FROM visitor_saved_site vs JOIN site s ON s.id = vs.site_id "
                        + "WHERE vs.visitor_id = ? ORDER BY vs.created_at DESC",
                (rs, i) -> new SavedSiteRow(rs.getObject("site_id", UUID.class), rs.getString("site_name"), rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                visitorId);
    }

    boolean siteExists(UUID siteId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM site WHERE id = ?", Integer.class, siteId);
        return count != null && count > 0;
    }

    void saveSite(UUID visitorId, UUID siteId, Instant now) {
        jdbc.update(
                "INSERT INTO visitor_saved_site (visitor_id, site_id, created_at) VALUES (?, ?, ?) ON CONFLICT (visitor_id, site_id) DO NOTHING",
                visitorId, siteId, ts(now));
    }

    void unsaveSite(UUID visitorId, UUID siteId) {
        jdbc.update("DELETE FROM visitor_saved_site WHERE visitor_id = ? AND site_id = ?", visitorId, siteId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> names(String json) {
        return new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
