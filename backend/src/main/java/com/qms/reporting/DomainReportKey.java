package com.qms.reporting;

import java.util.Optional;

/**
 * The five report keys ticket 51 adds to the catalogue (SRS §16.1): Appointment, Journey, Feedback, Notification
 * and Audit. Appointment and Journey close the two §15.3 KPIs ticket 50 explicitly left out ("appointment
 * adherence" and "journey completion") because their own source data — the Appointment and Journey tables this
 * ticket first reports on — belongs here (see {@code docs/traceability-matrix.md}, ticket 50's KPI row). Every key
 * is one row per entity (Appointment, Visit, feedback row, notification message, audit event) for the requested
 * filter, unlike the six ticket-50 keys' grouped-with-period-comparison shape (§16.1's "Appointment report" etc.
 * calls for a filterable list, not a KPI grid) — {@link DomainReportPage} is their shared answer shape, except
 * {@code audit} which reuses {@code com.qms.audit.AuditQueryService}'s own cursor-paged read directly.
 */
enum DomainReportKey {
    APPOINTMENT("appointment"),
    JOURNEY("journey"),
    FEEDBACK("feedback"),
    NOTIFICATION("notification"),
    AUDIT("audit");

    private final String wire;

    DomainReportKey(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    static Optional<DomainReportKey> fromWire(String key) {
        for (DomainReportKey k : values()) {
            if (k.wire.equals(key)) return Optional.of(k);
        }
        return Optional.empty();
    }
}
