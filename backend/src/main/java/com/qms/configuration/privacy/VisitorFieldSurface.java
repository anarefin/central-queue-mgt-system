package com.qms.configuration.privacy;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The two §25.3 (FR-SEC-020) surfaces this ticket gives an Org Admin runtime control over, each with the fixed set
 * of fields it may turn on or off. See {@code V45__privacy_controls.sql} for why the other surfaces of the same SRS
 * table (printed token, public display, announcement, agent console, report export) are not repeated here: each
 * already has its own admin-configurable seam from an earlier ticket.
 */
enum VisitorFieldSurface {

    /** FR-SEC-023: which OPTIONAL fields a walk-in registration captures at all; {@code name} and {@code phone} are
     * always captured (the minimum record itself) and so never appear here. */
    CAPTURE("capture", Set.of("email", "category", "purpose")),

    /** FR-SEC-020's "Kiosk confirmation | Name, category" row. */
    KIOSK_CONFIRMATION("kiosk_confirmation", Set.of("name", "category"));

    private final String wire;
    private final Set<String> fields;

    VisitorFieldSurface(String wire, Set<String> fields) {
        this.wire = wire;
        this.fields = fields;
    }

    String wire() {
        return wire;
    }

    Set<String> fields() {
        return fields;
    }

    static Optional<VisitorFieldSurface> fromWire(String wire) {
        return Arrays.stream(values()).filter(s -> s.wire.equals(wire)).findFirst();
    }
}
