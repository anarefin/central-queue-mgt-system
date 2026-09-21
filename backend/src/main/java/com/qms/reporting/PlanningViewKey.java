package com.qms.reporting;

import java.util.Optional;

/**
 * The two staffing-planning views ticket 51 adds under {@code POST /reports/{key}/run} (§16.2, FR-RPT-011,
 * FR-RPT-012): cross-tabs over a single requested period, not the grouped-with-previous-period-comparison shape
 * every §16.1 report key carries — neither FR-RPT-011 nor FR-RPT-012 asks for a trend against a prior period, only
 * a snapshot to plan staffing against.
 */
enum PlanningViewKey {
    PEAK_HOURS("peak-hours"),
    STAFFING_GAP("staffing-gap");

    private final String wire;

    PlanningViewKey(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    static Optional<PlanningViewKey> fromWire(String key) {
        for (PlanningViewKey k : values()) {
            if (k.wire.equals(key)) return Optional.of(k);
        }
        return Optional.empty();
    }
}
