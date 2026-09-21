package com.qms.reporting;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The detailed token report's own read path (§16.1, FR-RPT-002): every query here selects from
 * {@code reporting.ticket_fact} alone, never a live transactional table (§16's own "reports run against the
 * reporting store, never against the live transactional tables") — {@link ReportingRefreshReads} is the one class
 * that touches {@code ticket}/{@code ticket_event} for this context.
 */
@Repository
class DetailedTokenReportReads {

    /** The columns a caller may name in {@code sort} (FR-RPT-002 "sortable by any displayed column"), mapped to the
     * actual table column — a whitelist, never string-built from client input directly. */
    static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("token_number", "token_number"),
            Map.entry("visitor_code", "visitor_code"),
            Map.entry("visitor_name", "visitor_name"),
            Map.entry("visitor_category", "visitor_category"),
            Map.entry("service_group", "service_group_sort"),
            Map.entry("service", "service_sort"),
            Map.entry("channel", "channel"),
            Map.entry("priority_class", "priority_class_sort"),
            Map.entry("issued_at", "issued_at"),
            Map.entry("called_at", "called_at"),
            Map.entry("served_at", "served_at"),
            Map.entry("closed_at", "closed_at"),
            Map.entry("wait_seconds", "wait_seconds"),
            Map.entry("service_seconds", "service_seconds"),
            Map.entry("counter", "counter_label"),
            Map.entry("agent", "agent_name"),
            Map.entry("outcome", "outcome_sort"),
            Map.entry("transfers", "transfers"));

    static final String DEFAULT_SORT = "issued_at";

    record Row(
            UUID ticketId,
            String tokenNumber,
            String visitorCode,
            String visitorName,
            String visitorCategory,
            Map<String, String> serviceGroupName,
            Map<String, String> serviceName,
            String channel,
            Map<String, String> priorityClassName,
            Instant issuedAt,
            Instant calledAt,
            Instant servedAt,
            Instant closedAt,
            Integer waitSeconds,
            Integer serviceSeconds,
            String counterLabel,
            String agentName,
            Map<String, String> outcomeLabel,
            int transfers) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    DetailedTokenReportReads(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    long totalRows(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM reporting.ticket_fact WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, filter, allowedSites, allowedGroups);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** "Tickets issued" (§18.5, ADR-0006): chain heads only, never every row. */
    long ticketsIssued(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM reporting.ticket_fact WHERE is_chain_head = true");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, filter, allowedSites, allowedGroups);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    List<Row> page(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups, String sortColumn, boolean ascending, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT ticket_id, token_number, visitor_code, visitor_name, visitor_category, service_group_name, service_name,"
                        + " channel, priority_class_name, issued_at, called_at, served_at, closed_at, wait_seconds, service_seconds,"
                        + " counter_label, agent_name, outcome_label, transfers"
                        + " FROM reporting.ticket_fact WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, filter, allowedSites, allowedGroups);
        sql.append(" ORDER BY ").append(sortColumn).append(ascending ? " ASC" : " DESC").append(", ticket_id ASC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(
                sql.toString(),
                (rs, i) -> new Row(
                        rs.getObject("ticket_id", UUID.class),
                        rs.getString("token_number"),
                        rs.getString("visitor_code"),
                        rs.getString("visitor_name"),
                        rs.getString("visitor_category"),
                        names(rs.getString("service_group_name")),
                        names(rs.getString("service_name")),
                        rs.getString("channel"),
                        names(rs.getString("priority_class_name")),
                        instant(rs.getTimestamp("issued_at")),
                        instant(rs.getTimestamp("called_at")),
                        instant(rs.getTimestamp("served_at")),
                        instant(rs.getTimestamp("closed_at")),
                        (Integer) rs.getObject("wait_seconds"),
                        (Integer) rs.getObject("service_seconds"),
                        rs.getString("counter_label"),
                        rs.getString("agent_name"),
                        names(rs.getString("outcome_label")),
                        rs.getInt("transfers")),
                args.toArray());
    }

    private void appendFilters(StringBuilder sql, List<Object> args, DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups) {
        if (filter.from() != null) {
            sql.append(" AND issued_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND issued_at < ?");
            args.add(Timestamp.from(filter.to()));
        }
        if (filter.siteId() != null) {
            sql.append(" AND site_id = ?");
            args.add(filter.siteId());
        } else if (allowedSites != null) {
            appendIn(sql, args, "site_id", allowedSites);
        }
        if (filter.zoneId() != null) {
            sql.append(" AND zone_id = ?");
            args.add(filter.zoneId());
        }
        if (filter.serviceGroupId() != null) {
            sql.append(" AND service_group_id = ?");
            args.add(filter.serviceGroupId());
        } else if (allowedGroups != null) {
            appendIn(sql, args, "service_group_id", allowedGroups);
        }
        if (filter.serviceId() != null) {
            sql.append(" AND service_id = ?");
            args.add(filter.serviceId());
        }
        if (filter.agentId() != null) {
            sql.append(" AND agent_id = ?");
            args.add(filter.agentId());
        }
        if (filter.priorityClassId() != null) {
            sql.append(" AND priority_class_id = ?");
            args.add(filter.priorityClassId());
        }
        if (filter.channel() != null) {
            sql.append(" AND channel = ?");
            args.add(filter.channel());
        }
        if (filter.visitorCategory() != null) {
            sql.append(" AND visitor_category = ?");
            args.add(filter.visitorCategory());
        }
    }

    private void appendIn(StringBuilder sql, List<Object> args, String column, Set<UUID> ids) {
        if (ids.isEmpty()) {
            sql.append(" AND 1 = 0");
            return;
        }
        sql.append(" AND ").append(column).append(" IN (");
        boolean first = true;
        for (UUID id : ids) {
            sql.append(first ? "?" : ", ?");
            args.add(id);
            first = false;
        }
        sql.append(")");
    }

    private static Instant instant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> names(String json) {
        return json == null ? Map.of() : new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }
}
