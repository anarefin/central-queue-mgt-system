package com.qms.issuance;

import com.qms.issuance.TokenNumbering.ResetBoundary;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL for numbering rules and the reset ledger. Like the rest of issuance it reads the catalogue tables directly for
 * what it needs and writes none of them.
 */
@Repository
class NumberingRepository {

    /** An active Service with what numbering needs to know about it, for the scheduled reset. */
    record ActiveService(UUID serviceId, UUID groupId, UUID siteId, String servicePrefix, String groupPrefix, String timezone) {}

    /** A Service or Service group a rule can be set for, resolved to its site. */
    record Scope(UUID siteId, String timezone) {}

    private static final String RULE = "SELECT r.id, r.site_id, r.scope_type, r.scope_id, r.prefix_source, r.fixed_prefix, r.sequence_start, r.padding,"
            + " r.reset_boundary, r.reset_time, r.separator, r.created_at, r.updated_at FROM numbering_rule r";

    private static final RowMapper<NumberingRule> RULES = (rs, i) -> new NumberingRule(
            rs.getObject("id", UUID.class),
            rs.getObject("site_id", UUID.class),
            rs.getString("scope_type"),
            rs.getObject("scope_id", UUID.class),
            new NumberingSpec(
                    rs.getString("prefix_source"),
                    rs.getString("fixed_prefix"),
                    rs.getLong("sequence_start"),
                    rs.getInt("padding"),
                    ResetBoundary.fromWire(rs.getString("reset_boundary")),
                    rs.getObject("reset_time", LocalTime.class),
                    rs.getString("separator")),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    NumberingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- rules ------------------------------------------------------------------------------------------------

    Optional<NumberingRule> rule(String scopeType, UUID scopeId) {
        return jdbc.query(RULE + " WHERE r.scope_type = ? AND r.scope_id = ?", RULES, scopeType, scopeId).stream().findFirst();
    }

    /** The rules of a site whose Service or Service group still exists. */
    List<NumberingRule> rulesOfSite(UUID siteId) {
        return jdbc.query(
                RULE + " WHERE r.site_id = ? AND ((r.scope_type = 'service' AND EXISTS (SELECT 1 FROM service v WHERE v.id = r.scope_id))"
                        + " OR (r.scope_type = 'service_group' AND EXISTS (SELECT 1 FROM service_group g WHERE g.id = r.scope_id)))"
                        + " ORDER BY r.scope_type, r.created_at, r.id",
                RULES,
                siteId);
    }

    /** The rule that applies to a Service: its own, else its group's, else the built-in default. */
    NumberingSpec effective(UUID serviceId, UUID groupId) {
        return jdbc.query(
                        RULE + " WHERE (r.scope_type = 'service' AND r.scope_id = ?) OR (r.scope_type = 'service_group' AND r.scope_id = ?)"
                                + " ORDER BY (r.scope_type = 'service') DESC LIMIT 1",
                        RULES,
                        serviceId, groupId)
                .stream().findFirst().map(NumberingRule::spec).orElse(NumberingSpec.DEFAULT);
    }

    /** Every rule, keyed by scope type and id, for resolving many Services at once. */
    Map<String, NumberingSpec> allRules() {
        Map<String, NumberingSpec> rules = new HashMap<>();
        for (NumberingRule rule : jdbc.query(RULE, RULES)) rules.put(rule.scopeType() + "/" + rule.scopeId(), rule.spec());
        return rules;
    }

    void insert(NumberingRule rule) {
        NumberingSpec s = rule.spec();
        jdbc.update(
                "INSERT INTO numbering_rule (id, site_id, scope_type, scope_id, prefix_source, fixed_prefix, sequence_start, padding, reset_boundary,"
                        + " reset_time, separator, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                rule.id(), rule.siteId(), rule.scopeType(), rule.scopeId(), s.prefixSource(), s.fixedPrefix(), s.start(), s.padding(), s.boundary().wire(),
                s.resetTime(), s.separator(), ts(rule.createdAt()), ts(rule.updatedAt()));
    }

    void update(NumberingRule rule) {
        NumberingSpec s = rule.spec();
        jdbc.update(
                "UPDATE numbering_rule SET prefix_source = ?, fixed_prefix = ?, sequence_start = ?, padding = ?, reset_boundary = ?, reset_time = ?,"
                        + " separator = ?, updated_at = ? WHERE id = ?",
                s.prefixSource(), s.fixedPrefix(), s.start(), s.padding(), s.boundary().wire(), s.resetTime(), s.separator(), ts(rule.updatedAt()), rule.id());
    }

    void delete(UUID id) {
        jdbc.update("DELETE FROM numbering_rule WHERE id = ?", id);
    }

    // ---- what a rule is set for -------------------------------------------------------------------------------

    Optional<Scope> serviceScope(UUID serviceId) {
        return jdbc.query(
                        "SELECT g.site_id, s.timezone FROM service v JOIN service_group g ON g.id = v.service_group_id JOIN site s ON s.id = g.site_id WHERE v.id = ?",
                        (rs, i) -> new Scope(rs.getObject(1, UUID.class), rs.getString(2)),
                        serviceId)
                .stream().findFirst();
    }

    Optional<Scope> groupScope(UUID groupId) {
        return jdbc.query(
                        "SELECT g.site_id, s.timezone FROM service_group g JOIN site s ON s.id = g.site_id WHERE g.id = ?",
                        (rs, i) -> new Scope(rs.getObject(1, UUID.class), rs.getString(2)),
                        groupId)
                .stream().findFirst();
    }

    /** Tickets in the queue for a Service, or for every Service of a group: what a rule change leaves numbered as they are (FR-CFG-041). */
    int waitingTickets(String scopeType, UUID scopeId) {
        String column = NumberingRule.SERVICE.equals(scopeType) ? "t.service_id" : "t.service_group_id";
        Integer count = jdbc.queryForObject("SELECT count(*) FROM ticket t WHERE " + column + " = ? AND t.state IN ('waiting', 'paused')", Integer.class, scopeId);
        return count == null ? 0 : count;
    }

    /** The active Services of a group, in display order, for previewing a group's numbering. */
    List<ActiveService> activeServicesOfGroup(UUID groupId) {
        return jdbc.query(ACTIVE_SERVICES + " AND g.id = ? ORDER BY v.display_order, v.token_prefix, v.id", ACTIVE, groupId);
    }

    Optional<ActiveService> service(UUID serviceId) {
        return jdbc.query(
                        "SELECT v.id AS service_id, v.service_group_id, g.site_id, v.token_prefix AS service_prefix, g.token_prefix AS group_prefix, s.timezone"
                                + " FROM service v JOIN service_group g ON g.id = v.service_group_id JOIN site s ON s.id = g.site_id WHERE v.id = ?",
                        ACTIVE,
                        serviceId)
                .stream().findFirst();
    }

    /** Every Service that can issue tickets now. */
    List<ActiveService> activeServices() {
        return jdbc.query(ACTIVE_SERVICES, ACTIVE);
    }

    private static final String ACTIVE_SERVICES =
            "SELECT v.id AS service_id, v.service_group_id, g.site_id, v.token_prefix AS service_prefix, g.token_prefix AS group_prefix, s.timezone"
                    + " FROM service v JOIN service_group g ON g.id = v.service_group_id JOIN site s ON s.id = g.site_id"
                    + " WHERE v.active AND g.active AND s.active";

    private static final RowMapper<ActiveService> ACTIVE = (rs, i) -> new ActiveService(
            rs.getObject("service_id", UUID.class),
            rs.getObject("service_group_id", UUID.class),
            rs.getObject("site_id", UUID.class),
            rs.getString("service_prefix"),
            rs.getString("group_prefix"),
            rs.getString("timezone"));

    // ---- the reset ledger -------------------------------------------------------------------------------------

    boolean resetRecorded(String scopeKey, String resetKey) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM numbering_reset WHERE scope_key = ? AND reset_key = ?)", Boolean.class, scopeKey, resetKey));
    }

    /** Whether the scope has been opened for an earlier period, so that a late opening is a replay rather than a first start. */
    boolean hasEarlierReset(String scopeKey, Instant periodStart) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM numbering_reset WHERE scope_key = ? AND period_start < ?)", Boolean.class, scopeKey, ts(periodStart)));
    }

    /** Records that a period was opened; false when another caller recorded it first. */
    boolean recordReset(UUID id, UUID siteId, String scopeKey, String resetKey, Instant periodStart, String triggeredBy, Instant at) {
        return jdbc.update(
                        "INSERT INTO numbering_reset (id, site_id, scope_key, reset_key, period_start, triggered_by, executed_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
                                + " ON CONFLICT (scope_key, reset_key) DO NOTHING",
                        id, siteId, scopeKey, resetKey, ts(periodStart), triggeredBy, ts(at))
                > 0;
    }

    /**
     * The highest number reserved for this scope under any other reset key whose period began at or after
     * {@code periodStart}, or empty. A period that opens while such numbers exist (a rule changed from daily to weekly
     * part-way through the week, say) continues after them, so a token number is never issued twice inside the period.
     */
    Optional<Long> highestReservedSince(String scopeKey, String resetKey, Instant periodStart) {
        Long highest = jdbc.queryForObject(
                "SELECT max(b.next_value) - 1 FROM sequence_block b JOIN numbering_reset r ON r.scope_key = b.scope_key AND r.reset_key = b.reset_key"
                        + " WHERE b.scope_key = ? AND b.reset_key <> ? AND r.period_start >= ?",
                Long.class,
                scopeKey, resetKey, ts(periodStart));
        return Optional.ofNullable(highest);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
