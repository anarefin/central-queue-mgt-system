package com.qms.reporting;

import java.util.Optional;

/**
 * The six aggregate report keys ticket 50 adds to the catalogue (SRS §16.1): the detailed token report (ticket 48)
 * is one row per ticket, and the break report (ticket 16, {@code com.qms.session.BreakReportService}) already
 * exists as its own catalogue entry; every other §16.1 row this ticket builds groups {@code reporting.ticket_fact}
 * (and, for the counter report, the live {@code counter_session}/{@code break_record} tables the same way {@code
 * BreakReportService} already reads them) to one row per grain value over a single requested period.
 */
enum OperationalReportKey {
    VISITOR_FLOW("visitor-flow"),
    COUNTER("counter"),
    AGENT("agent"),
    SERVICE("service"),
    DEPARTMENT("department"),
    SITE("site");

    private final String wire;

    OperationalReportKey(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    static Optional<OperationalReportKey> fromWire(String key) {
        for (OperationalReportKey k : values()) {
            if (k.wire.equals(key)) return Optional.of(k);
        }
        return Optional.empty();
    }
}
