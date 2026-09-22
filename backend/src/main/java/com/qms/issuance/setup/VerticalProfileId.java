package com.qms.issuance.setup;

import java.util.Arrays;
import java.util.Optional;

/**
 * The five shipped vertical profiles (SRS §3.4). This is a closed set of seed-file identifiers, not a branch on
 * industry: every field that differs between them (labels, starter catalogue, priority classes, numbering, report
 * and KPI defaults, feature flags) lives in {@code profiles/<wire>.json} data, never in an {@code if} here
 * (CFG-001, NFR-MNT-005).
 */
public enum VerticalProfileId {
    BANKING("banking"),
    HEALTHCARE("healthcare"),
    PRODUCER_SERVICES("producer_services"),
    GOVERNMENT("government"),
    GENERIC("generic");

    private final String wire;

    VerticalProfileId(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<VerticalProfileId> tryFromWire(String wire) {
        return Arrays.stream(values()).filter(id -> id.wire.equals(wire)).findFirst();
    }
}
