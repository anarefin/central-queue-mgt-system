package com.qms.reporting;

import java.util.Arrays;
import java.util.Optional;

/**
 * The closed set of data classes {@code reporting.retention_policy} governs (FR-SEC-032, ticket 53): {@code
 * TICKET_DETAIL} is the PII-bearing row in {@code reporting.ticket_fact}, {@code TICKET_AGGREGATE} is what that
 * same row becomes once anonymised, {@code AUDIT} is {@code audit_log}. Only {@code TICKET_DETAIL} has a
 * meaningful {@code mode} (purge vs. anonymize, "per the client's choice", FR-RPT-021); the other two are always
 * eventually purged outright, so {@link RetentionPolicyService} refuses a caller who tries to set a different mode
 * on them.
 */
enum RetentionDataClass {
    TICKET_DETAIL("ticket_detail"),
    TICKET_AGGREGATE("ticket_aggregate"),
    AUDIT("audit");

    private final String wire;

    RetentionDataClass(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    static Optional<RetentionDataClass> fromWire(String wire) {
        return Arrays.stream(values()).filter(c -> c.wire.equals(wire)).findFirst();
    }
}
