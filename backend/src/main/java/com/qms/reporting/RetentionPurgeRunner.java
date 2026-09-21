package com.qms.reporting;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.Profiles;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Function;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One nightly sweep (ticket 53, FR-RPT-021/022, FR-SEC-032/043) against {@code reporting.retention_policy}'s own
 * three data classes:
 *
 * <ul>
 *   <li>{@code ticket_detail} - rows in {@code reporting.ticket_fact} not yet anonymised, older than its own
 *       retention: purged outright if the policy's {@code mode} is {@code purge}, or reduced to an anonymised
 *       aggregate (visitor identifiers cleared, {@code anonymized_at} stamped) if {@code anonymize} - the
 *       "purged or reduced to anonymised aggregates, per the client's choice" FR-RPT-021 asks for.
 *   <li>{@code ticket_aggregate} - rows already anonymised, older than its own (longer) retention: purged
 *       outright. Measuring both classes' retention from the same {@code issued_at} (rather than, say, {@code
 *       anonymized_at}) is what lets an anonymised row simply keep living in {@code reporting.ticket_fact} under
 *       the longer period instead of needing a second, separate table to "survive" into (FR-RPT-022).
 *   <li>{@code audit} - rows in {@code audit_log} older than its own, independently configured retention
 *       (FR-SEC-043): the one privileged path the append-only trigger's own migration comment (V3) already
 *       anticipated, admitted here for one statement only via a transaction-local flag ({@code SET LOCAL
 *       qms.audit_purge = 'on'}, V44) that is gone again the instant this method's own transaction ends.
 * </ul>
 *
 * Every non-empty sweep writes exactly one audit entry carrying the aggregate counts above and nothing else
 * (FR-SEC-032: "the purge job MUST log what it removed in aggregate") - never a per-row list, so the log entry
 * itself can never carry the PII the sweep just purged.
 */
@Component
@Profile(Profiles.SERVING)
class RetentionPurgeRunner {

    private final JdbcTemplate jdbc;
    private final RetentionPolicyRepository policies;
    private final AuditWriter audit;
    private final Clock clock;

    RetentionPurgeRunner(JdbcTemplate jdbc, RetentionPolicyRepository policies, AuditWriter audit, Clock clock) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    RetentionPurgeSummary tick() {
        Instant now = clock.instant();
        Map<String, RetentionPolicyRepository.Row> byClass = policies.list().stream().collect(
                java.util.stream.Collectors.toMap(RetentionPolicyRepository.Row::dataClass, Function.identity()));

        int detailAnonymized = 0;
        int detailPurged = 0;
        RetentionPolicyRepository.Row detail = byClass.get(RetentionDataClass.TICKET_DETAIL.wire());
        if (detail != null) {
            Timestamp cutoff = cutoff(now, detail.retentionMonths());
            if ("purge".equals(detail.mode())) {
                detailPurged = jdbc.update("DELETE FROM reporting.ticket_fact WHERE anonymized_at IS NULL AND issued_at < ?", cutoff);
            } else {
                detailAnonymized = jdbc.update(
                        "UPDATE reporting.ticket_fact SET visitor_id = NULL, visitor_code = NULL, visitor_name = NULL, anonymized_at = ?"
                                + " WHERE anonymized_at IS NULL AND issued_at < ?",
                        Timestamp.from(now), cutoff);
            }
        }

        int aggregatePurged = 0;
        RetentionPolicyRepository.Row aggregate = byClass.get(RetentionDataClass.TICKET_AGGREGATE.wire());
        if (aggregate != null) {
            Timestamp cutoff = cutoff(now, aggregate.retentionMonths());
            aggregatePurged = jdbc.update("DELETE FROM reporting.ticket_fact WHERE anonymized_at IS NOT NULL AND issued_at < ?", cutoff);
        }

        int auditPurged = 0;
        RetentionPolicyRepository.Row auditPolicy = byClass.get(RetentionDataClass.AUDIT.wire());
        if (auditPolicy != null) {
            Timestamp cutoff = cutoff(now, auditPolicy.retentionMonths());
            jdbc.execute("SET LOCAL qms.audit_purge = 'on'");
            auditPurged = jdbc.update("DELETE FROM audit_log WHERE occurred_at < ?", cutoff);
        }

        RetentionPurgeSummary summary = new RetentionPurgeSummary(detailAnonymized, detailPurged, aggregatePurged, auditPurged);
        if (summary.total() > 0) {
            audit.record(AuditEvent.of("retention.purge_run", "retention", null)
                    .withAfter(summary.asMap())
                    .withReason("scheduled retention purge"));
        }
        return summary;
    }

    private static Timestamp cutoff(Instant now, int retentionMonths) {
        return Timestamp.from(now.atZone(ZoneOffset.UTC).minusMonths(retentionMonths).toInstant());
    }
}
