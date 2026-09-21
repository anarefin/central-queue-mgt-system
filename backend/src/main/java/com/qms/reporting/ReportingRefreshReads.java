package com.qms.reporting;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The refresh sweep's own SQL (ticket 48, §18.5, FR-RPT-020): reads the live transactional tables (`ticket`,
 * `ticket_event` and the dimensions a ticket points at) and writes {@code reporting.ticket_fact} — the one place in
 * this context allowed to touch them, the same "read another context's table directly" shape
 * {@code com.qms.dashboard.DashboardReads} already sets for a live view. {@link DetailedTokenReportReads} is kept
 * entirely apart from this class: it reads {@code reporting.ticket_fact} alone, never the tables this one does.
 */
@Repository
class ReportingRefreshReads {

    private final JdbcTemplate jdbc;

    ReportingRefreshReads(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Null means "never refreshed" (the seed row this migration inserts). */
    Instant watermark() {
        return jdbc.queryForObject("SELECT last_recorded_at FROM reporting.refresh_watermark", (rs, i) -> toInstant(rs.getTimestamp("last_recorded_at")));
    }

    /** Null only when {@code ticket_event} itself is empty (a fresh database). */
    Instant maxEventRecordedAt() {
        return jdbc.query("SELECT max(recorded_at) AS m FROM ticket_event", rs -> rs.next() ? toInstant(rs.getTimestamp("m")) : null);
    }

    void advanceWatermark(Instant to) {
        jdbc.update("UPDATE reporting.refresh_watermark SET last_recorded_at = ?", Timestamp.from(to));
    }

    /**
     * Upserts every ticket with a {@code ticket_event} recorded after {@code since} into {@code reporting.ticket_fact},
     * denormalising every dimension the detailed token report names (§16.1) so it never has to join at read time.
     * {@code transfers} (ADR-0006) is computed by walking only the changed rows' own ancestry — never the whole
     * `ticket` table — so a busy chain of transfers costs no more than its own depth, however large the ticket table
     * has grown (FR-RPT-020's own "never slows the queue" applies to this sweep too).
     */
    int upsertChangedSince(Instant since, Instant refreshedAt) {
        Timestamp sinceTs = Timestamp.from(since);
        return jdbc.update(
                """
                WITH RECURSIVE changed AS (
                    SELECT DISTINCT ticket_id AS id FROM ticket_event WHERE recorded_at > ?
                ),
                ancestry(origin_id, ticket_id, predecessor_ticket_id, depth) AS (
                    SELECT chg.id, t.id, t.predecessor_ticket_id, 0
                    FROM ticket t JOIN changed chg ON chg.id = t.id
                    UNION ALL
                    SELECT a.origin_id, p.id, p.predecessor_ticket_id, a.depth + 1
                    FROM ticket p JOIN ancestry a ON p.id = a.predecessor_ticket_id
                ),
                chain AS (
                    SELECT origin_id AS ticket_id, max(depth) AS transfers FROM ancestry GROUP BY origin_id
                )
                INSERT INTO reporting.ticket_fact (
                    ticket_id, token_number, site_id, site_name, zone_id, zone_name,
                    service_group_id, service_group_name, service_group_sort,
                    service_id, service_name, service_sort,
                    agent_id, agent_name, counter_id, counter_label,
                    visitor_id, visitor_code, visitor_name, visitor_category,
                    priority_class_id, priority_class_name, priority_class_sort,
                    channel, outcome_code_id, outcome_code, outcome_label, outcome_sort,
                    state, predecessor_ticket_id, is_chain_head, transfers,
                    issued_at, called_at, served_at, closed_at, wait_seconds, service_seconds, refreshed_at
                )
                SELECT
                    t.id, t.token_number, t.site_id, s.name, t.zone_id, z.name,
                    t.service_group_id, sg.name_i18n, reporting.i18n_sort_key(sg.name_i18n),
                    t.service_id, sv.name_i18n, reporting.i18n_sort_key(sv.name_i18n),
                    t.agent_id, u.display_name, t.counter_id, ctr.label,
                    t.visitor_id, v.external_code, v.name, v.category,
                    pc.id, pc.name_i18n, reporting.i18n_sort_key(pc.name_i18n),
                    t.origin_channel, t.outcome_code_id, oc.code, oc.label_i18n, reporting.i18n_sort_key(oc.label_i18n),
                    t.state, t.predecessor_ticket_id, (t.predecessor_ticket_id IS NULL), ch.transfers,
                    t.issued_at, t.called_at, t.served_at, t.closed_at, t.wait_seconds, t.service_seconds, ?
                FROM ticket t
                JOIN changed chg ON chg.id = t.id
                JOIN chain ch ON ch.ticket_id = t.id
                JOIN site s ON s.id = t.site_id
                LEFT JOIN zone z ON z.id = t.zone_id
                JOIN service_group sg ON sg.id = t.service_group_id
                JOIN service sv ON sv.id = t.service_id
                LEFT JOIN users u ON u.id = t.agent_id
                LEFT JOIN counter ctr ON ctr.id = t.counter_id
                LEFT JOIN visitor v ON v.id = t.visitor_id
                LEFT JOIN priority_class pc ON pc.id = coalesce(t.priority_class_id, (SELECT id FROM priority_class WHERE is_default))
                LEFT JOIN outcome_code oc ON oc.id = t.outcome_code_id
                ON CONFLICT (ticket_id) DO UPDATE SET
                    token_number = EXCLUDED.token_number, site_id = EXCLUDED.site_id, site_name = EXCLUDED.site_name,
                    zone_id = EXCLUDED.zone_id, zone_name = EXCLUDED.zone_name,
                    service_group_id = EXCLUDED.service_group_id, service_group_name = EXCLUDED.service_group_name,
                    service_group_sort = EXCLUDED.service_group_sort,
                    service_id = EXCLUDED.service_id, service_name = EXCLUDED.service_name, service_sort = EXCLUDED.service_sort,
                    agent_id = EXCLUDED.agent_id, agent_name = EXCLUDED.agent_name,
                    counter_id = EXCLUDED.counter_id, counter_label = EXCLUDED.counter_label,
                    visitor_id = EXCLUDED.visitor_id, visitor_code = EXCLUDED.visitor_code,
                    visitor_name = EXCLUDED.visitor_name, visitor_category = EXCLUDED.visitor_category,
                    priority_class_id = EXCLUDED.priority_class_id, priority_class_name = EXCLUDED.priority_class_name,
                    priority_class_sort = EXCLUDED.priority_class_sort,
                    channel = EXCLUDED.channel, outcome_code_id = EXCLUDED.outcome_code_id,
                    outcome_code = EXCLUDED.outcome_code, outcome_label = EXCLUDED.outcome_label, outcome_sort = EXCLUDED.outcome_sort,
                    state = EXCLUDED.state, predecessor_ticket_id = EXCLUDED.predecessor_ticket_id,
                    is_chain_head = EXCLUDED.is_chain_head, transfers = EXCLUDED.transfers,
                    issued_at = EXCLUDED.issued_at, called_at = EXCLUDED.called_at, served_at = EXCLUDED.served_at,
                    closed_at = EXCLUDED.closed_at, wait_seconds = EXCLUDED.wait_seconds, service_seconds = EXCLUDED.service_seconds,
                    refreshed_at = EXCLUDED.refreshed_at
                """,
                sinceTs, Timestamp.from(refreshedAt));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
