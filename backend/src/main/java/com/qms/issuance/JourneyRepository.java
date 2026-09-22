package com.qms.issuance;

import com.qms.configuration.privacy.PiiCipher;
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

/**
 * SQL for Journeys (ticket 31, ADR-0007): the feature flag, templates as issuance reads them (never writes them; that
 * is {@code configuration.catalogue}'s job, the same split as the Service catalogue itself), and the stops of a Visit's
 * Journey.
 */
@Repository
class JourneyRepository {

    /** A template as issuance needs it: whether it is still offered and how its stops are issued. */
    record TemplateRow(UUID id, UUID serviceGroupId, boolean ordered, boolean active) {}

    /** One planned stop of a Visit's Journey, with the ticket that realises it once issued. */
    record StopRow(UUID id, int seq, UUID serviceId, Map<String, String> serviceNames, UUID ticketId) {}

    /** The next stop of an ordered Journey still waiting to be issued, and the Visit it belongs to. */
    record NextStop(UUID id, UUID visitId, UUID serviceId) {}

    /** What continuing an ordered Journey after a stop completes needs to know about that stop's ticket. */
    record StopContext(UUID visitId, boolean journeyOrdered, UUID visitorId, String purposeNote) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final PiiCipher cipher;

    JourneyRepository(JdbcTemplate jdbc, JsonMapper mapper, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.cipher = cipher;
    }

    boolean enabled() {
        Boolean enabled = jdbc.queryForObject("SELECT enabled FROM journey_settings WHERE id = 1", Boolean.class);
        return Boolean.TRUE.equals(enabled);
    }

    void setEnabled(boolean enabled, Instant now) {
        jdbc.update("UPDATE journey_settings SET enabled = ?, updated_at = ? WHERE id = 1", enabled, ts(now));
    }

    Optional<TemplateRow> template(UUID id) {
        return jdbc.query(
                        "SELECT id, service_group_id, ordered, active FROM journey_template WHERE id = ?",
                        (rs, i) -> new TemplateRow(rs.getObject("id", UUID.class), rs.getObject("service_group_id", UUID.class), rs.getBoolean("ordered"), rs.getBoolean("active")),
                        id)
                .stream().findFirst();
    }

    /** A template's stops, in order, only when every Service on it is still active (a deactivated Service drops its templates from this list). */
    List<UUID> templateStopServiceIds(UUID templateId) {
        return jdbc.query(
                "SELECT service_id FROM journey_template_stop WHERE template_id = ? ORDER BY seq",
                (rs, i) -> rs.getObject("service_id", UUID.class),
                templateId);
    }

    /** Active templates of the Service groups of one Site, for Reception's picker (FR-QUE-060). */
    record TemplateSummary(UUID id, Map<String, String> nameI18n, boolean ordered, List<StopSummary> stops) {}

    record StopSummary(int seq, UUID serviceId, Map<String, String> serviceNames) {}

    List<TemplateSummary> activeTemplatesForSite(UUID siteId) {
        record Row(UUID templateId, Map<String, String> names, boolean ordered, int seq, UUID serviceId, Map<String, String> serviceNames) {}
        List<Row> rows = jdbc.query(
                "SELECT jt.id AS template_id, jt.name_i18n, jt.ordered, jts.seq, jts.service_id, sv.name_i18n AS service_names"
                        + " FROM journey_template jt JOIN service_group g ON g.id = jt.service_group_id"
                        + " JOIN journey_template_stop jts ON jts.template_id = jt.id JOIN service sv ON sv.id = jts.service_id"
                        + " WHERE g.site_id = ? AND jt.active AND g.active AND sv.active"
                        + " ORDER BY jt.display_order, jt.id, jts.seq",
                (rs, i) -> new Row(
                        rs.getObject("template_id", UUID.class),
                        names(rs.getString("name_i18n")),
                        rs.getBoolean("ordered"),
                        rs.getInt("seq"),
                        rs.getObject("service_id", UUID.class),
                        names(rs.getString("service_names"))),
                siteId);
        Map<UUID, List<StopSummary>> stopsByTemplate = new LinkedHashMap<>();
        Map<UUID, Row> firstRowOf = new LinkedHashMap<>();
        for (Row row : rows) {
            firstRowOf.putIfAbsent(row.templateId(), row);
            stopsByTemplate.computeIfAbsent(row.templateId(), id -> new ArrayList<>()).add(new StopSummary(row.seq(), row.serviceId(), row.serviceNames()));
        }
        List<TemplateSummary> summaries = new ArrayList<>();
        firstRowOf.forEach((id, row) -> summaries.add(new TemplateSummary(id, row.names(), row.ordered(), stopsByTemplate.get(id))));
        return summaries;
    }

    void setVisitJourney(UUID visitId, UUID templateId, boolean ordered) {
        jdbc.update("UPDATE visit SET journey_template_id = ?, journey_ordered = ? WHERE id = ?", templateId, ordered, visitId);
    }

    /** Plans the Visit's stops in order, one row per Service, with no ticket yet (FR-QUE-060). */
    List<UUID> insertStops(UUID visitId, List<UUID> serviceIds) {
        List<UUID> stopIds = new ArrayList<>();
        int seq = 1;
        for (UUID serviceId : serviceIds) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO journey_stop (id, visit_id, service_id, seq) VALUES (?, ?, ?, ?)", id, visitId, serviceId, seq);
            stopIds.add(id);
            seq++;
        }
        return stopIds;
    }

    void linkTicket(UUID stopId, UUID ticketId) {
        jdbc.update("UPDATE journey_stop SET ticket_id = ? WHERE id = ?", ticketId, stopId);
    }

    /** Every stop of a Visit's Journey, in order, whether or not it has been issued yet (FR-QUE-062, FR-AGT-031). */
    List<StopRow> stopsOfVisit(UUID visitId) {
        return jdbc.query(
                "SELECT js.id, js.seq, js.service_id, sv.name_i18n, js.ticket_id FROM journey_stop js JOIN service sv ON sv.id = js.service_id"
                        + " WHERE js.visit_id = ? ORDER BY js.seq",
                (rs, i) -> new StopRow(rs.getObject("id", UUID.class), rs.getInt("seq"), rs.getObject("service_id", UUID.class), names(rs.getString("name_i18n")), rs.getObject("ticket_id", UUID.class)),
                visitId);
    }

    /** The other stops of a ticket's own Visit, for the console (FR-AGT-031); empty when the ticket is not part of a Journey. */
    List<StopRow> otherStopsOf(UUID ticketId) {
        return jdbc.query(
                "SELECT js.id, js.seq, js.service_id, sv.name_i18n, js.ticket_id FROM journey_stop js JOIN service sv ON sv.id = js.service_id"
                        + " WHERE js.visit_id = (SELECT visit_id FROM ticket WHERE id = ?) AND js.ticket_id IS DISTINCT FROM ? ORDER BY js.seq",
                (rs, i) -> new StopRow(rs.getObject("id", UUID.class), rs.getInt("seq"), rs.getObject("service_id", UUID.class), names(rs.getString("name_i18n")), rs.getObject("ticket_id", UUID.class)),
                ticketId, ticketId);
    }

    /** The earliest not-yet-issued stop of a Visit's Journey (FR-QUE-061), empty once every stop has a ticket. */
    Optional<NextStop> nextUnissuedStop(UUID visitId) {
        return jdbc.query(
                        "SELECT id, visit_id, service_id FROM journey_stop WHERE visit_id = ? AND ticket_id IS NULL ORDER BY seq LIMIT 1",
                        (rs, i) -> new NextStop(rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class), rs.getObject("service_id", UUID.class)),
                        visitId)
                .stream().findFirst();
    }

    /** Whether {@code ticketId} realises a stop of an ordered Journey, and what the next stop should inherit if it does. */
    Optional<StopContext> stopContext(UUID ticketId) {
        return jdbc.query(
                        "SELECT js.visit_id, v.journey_ordered, t.visitor_id, t.purpose_note FROM journey_stop js"
                                + " JOIN visit v ON v.id = js.visit_id JOIN ticket t ON t.id = js.ticket_id WHERE js.ticket_id = ?",
                        (rs, i) -> new StopContext(
                                rs.getObject("visit_id", UUID.class), rs.getBoolean("journey_ordered"), rs.getObject("visitor_id", UUID.class),
                                cipher.decrypt(rs.getString("purpose_note"))),
                        ticketId)
                .stream().findFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> names(String json) {
        return new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
