package com.qms.device;

import com.qms.platform.i18n.Messages;
import com.qms.queue.QueueReads;
import com.qms.queue.WaitEstimate;
import com.qms.queue.WaitEstimates;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The live "now serving" reads of a zone's display board (SRS §12, ticket 28, FR-DSP-004, FR-DSP-005): one row per
 * active Counter of the zone (its label and, when a ticket is called or serving there, the token, Service and staff
 * name) and the next-N strip of every Service any of the zone's Counters serve. This is the one place that
 * computation lives, shared by {@code GET /devices/{id}/display-state} (the resume-after-power-loss read, FR-DSP-012)
 * and the {@code zone:} realtime topic's snapshot ({@link DisplayTopics}), so the two never drift apart.
 *
 * <p>Deliberately not filtered by a display's own assignment (FR-DSP-002): several displays can watch the same zone
 * with different assignments, and the {@code zone:} topic is one shared feed per zone, not one per display. Each
 * display narrows this down to its own Counters or Services itself (device package's frontend), the same filter
 * applied to the initial read and to every live update after it.
 */
@Repository
class DisplayStateReads {

    /** How many of the next tickets a queue group lists; generous enough that every display's own {@code next_n} fits inside it. */
    static final int NEXT_MAX = 10;

    /** FR-SEC-021, ticket 54: the neutral label a clinical-sensitivity Site's own displays/announcements show
     * instead of any real service or service-group name. */
    static final String NEUTRAL_LABEL_KEY = "privacy.neutralService";

    private final JdbcTemplate jdbc;
    private final QueueReads queues;
    private final WaitEstimates waitEstimates;
    private final JsonMapper mapper;
    private final Messages messages;

    DisplayStateReads(JdbcTemplate jdbc, QueueReads queues, WaitEstimates waitEstimates, JsonMapper mapper, Messages messages) {
        this.jdbc = jdbc;
        this.queues = queues;
        this.waitEstimates = waitEstimates;
        this.mapper = mapper;
        this.messages = messages;
    }

    record ServingRow(
            UUID counterId,
            String counterLabel,
            String tokenNumber,
            String state,
            UUID serviceId,
            Map<String, String> serviceNames,
            String staffName,
            String tokenPrefix,
            Map<String, String> tokenPrefixSpoken,
            boolean announceVisitorName) {}

    record NextTicket(String tokenNumber, int position) {}

    record NextGroup(UUID serviceId, Map<String, String> serviceNames, List<NextTicket> tokens) {}

    /** One Service's lobby summary (ticket 30, FR-DSP-003): how many are waiting and the estimated wait for a visitor
     * joining the back of the queue right now. */
    record SummaryRow(UUID serviceId, Map<String, String> serviceNames, int waitingCount, WaitEstimate estimate) {}

    /** The site a zone belongs to, or empty when the zone does not exist. */
    Optional<UUID> siteIdOfZone(UUID zoneId) {
        return jdbc.query("SELECT site_id FROM zone WHERE id = ?", (rs, i) -> rs.getObject("site_id", UUID.class), zoneId).stream().findFirst();
    }

    /**
     * One row per active Counter of the zone, called/serving ticket columns null when nothing is happening there.
     * {@code token_prefix_spoken} (ticket 29, FR-DSP-030) is aggregated per language with a correlated subquery
     * rather than a join, since a prefix may have zero to many spoken forms and this stays one row per Counter.
     */
    List<ServingRow> serving(UUID zoneId) {
        boolean clinical = clinicalSensitivity(zoneId);
        return jdbc.query(
                "SELECT c.id AS counter_id, c.label, t.token_number, t.state, v.id AS service_id, v.name_i18n AS service_names,"
                        + " coalesce(nullif(u.display_name, ''), u.username) AS staff_name, v.token_prefix, v.announce_visitor_name,"
                        + " (SELECT jsonb_object_agg(f.language, f.spoken_text) FROM token_prefix_spoken_form f WHERE f.prefix = v.token_prefix) AS token_prefix_spoken"
                        + " FROM counter c"
                        + " LEFT JOIN ticket t ON t.counter_id = c.id AND t.state IN ('called', 'serving')"
                        + " LEFT JOIN service v ON v.id = t.service_id"
                        + " LEFT JOIN users u ON u.id = t.agent_id"
                        + " WHERE c.zone_id = ? AND c.active"
                        + " ORDER BY lower(c.label), c.id",
                (rs, i) -> servingRow(rs, clinical),
                zoneId);
    }

    /** The next-N strip of every Service any active Counter of the zone serves, in no particular order between groups. */
    List<NextGroup> next(UUID zoneId) {
        boolean clinical = clinicalSensitivity(zoneId);
        return serviceIdsOfZone(zoneId).stream().map(serviceId -> nextGroup(serviceId, clinical)).toList();
    }

    /** The `summary_board` layout's per-Service lobby summary (FR-DSP-003): every Service any active Counter of the
     * zone serves, with how many are waiting and the estimated wait for a new arrival (SRS §10.5, FR-QUE-040..042). */
    List<SummaryRow> summary(UUID zoneId) {
        boolean clinical = clinicalSensitivity(zoneId);
        return serviceIdsOfZone(zoneId).stream().map(serviceId -> summaryRow(serviceId, clinical)).toList();
    }

    /** FR-SEC-021: the Site a zone belongs to, once per call rather than once per row, since it never varies within one. */
    private boolean clinicalSensitivity(UUID zoneId) {
        return Boolean.TRUE.equals(jdbc.query(
                        "SELECT s.clinical_sensitivity FROM zone z JOIN site s ON s.id = z.site_id WHERE z.id = ?",
                        (rs, i) -> rs.getBoolean("clinical_sensitivity"),
                        zoneId)
                .stream()
                .findFirst()
                .orElse(false));
    }

    private SummaryRow summaryRow(UUID serviceId, boolean clinical) {
        int waiting = queues.waitingCount(serviceId);
        return new SummaryRow(serviceId, serviceNames(serviceId, clinical), waiting, waitEstimates.ahead(serviceId, waiting));
    }

    private List<UUID> serviceIdsOfZone(UUID zoneId) {
        return jdbc.query(
                "SELECT DISTINCT cs.service_id FROM counter_service cs JOIN counter c ON c.id = cs.counter_id"
                        + " WHERE c.zone_id = ? AND c.active AND cs.service_id IN (SELECT id FROM service WHERE active)",
                (rs, i) -> rs.getObject("service_id", UUID.class),
                zoneId);
    }

    private NextGroup nextGroup(UUID serviceId, boolean clinical) {
        List<NextTicket> tokens = queues.next(serviceId, NEXT_MAX).stream()
                .map(entry -> new NextTicket(entry.tokenNumber(), entry.position()))
                .toList();
        return new NextGroup(serviceId, serviceNames(serviceId, clinical), tokens);
    }

    private Map<String, String> serviceNames(UUID serviceId, boolean clinical) {
        if (clinical) return messages.allLanguages(NEUTRAL_LABEL_KEY);
        return jdbc.query("SELECT name_i18n FROM service WHERE id = ?", (rs, i) -> names(rs.getString("name_i18n")), serviceId)
                .stream().findFirst().orElse(Map.of());
    }

    private ServingRow servingRow(ResultSet rs, boolean clinical) throws SQLException {
        UUID serviceId = rs.getObject("service_id", UUID.class);
        return new ServingRow(
                rs.getObject("counter_id", UUID.class),
                rs.getString("label"),
                rs.getString("token_number"),
                rs.getString("state"),
                serviceId,
                serviceId == null ? Map.of() : (clinical ? messages.allLanguages(NEUTRAL_LABEL_KEY) : names(rs.getString("service_names"))),
                rs.getString("staff_name"),
                rs.getString("token_prefix"),
                names(rs.getString("token_prefix_spoken")),
                rs.getBoolean("announce_visitor_name"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> names(String json) {
        return json == null ? Map.of() : new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }
}
