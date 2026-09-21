package com.qms.reporting;

import java.util.Map;

/** One purge sweep's own aggregate counts (FR-SEC-032: "the purge job MUST log what it removed in aggregate") -
 * never a row-level list, so the log entry it becomes can never itself carry PII. */
record RetentionPurgeSummary(int ticketDetailAnonymized, int ticketDetailPurged, int ticketAggregatePurged, int auditPurged) {

    int total() {
        return ticketDetailAnonymized + ticketDetailPurged + ticketAggregatePurged + auditPurged;
    }

    Map<String, Object> asMap() {
        return Map.of(
                "ticket_detail_anonymized", ticketDetailAnonymized,
                "ticket_detail_purged", ticketDetailPurged,
                "ticket_aggregate_purged", ticketAggregatePurged,
                "audit_purged", auditPurged);
    }
}
