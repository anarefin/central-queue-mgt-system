package com.qms.issuance.setup;

import java.util.Arrays;
import java.util.Optional;

/**
 * The closed vocabulary of feature-flag keys a vertical profile turns on or off (CFG-001, SRS §3.3.6): the same six
 * keys every shipped {@code profiles/<wire>.json} carries under {@code feature_flags}. Never an industry branch in
 * code; this only bounds which keys {@code PUT /setup/feature-flags/{key}} (CFG-003) may write.
 */
enum FeatureFlagKey {
    APPOINTMENT("appointment"),
    VIRTUAL_QUEUE("virtual_queue"),
    JOURNEY("journey"),
    MULTI_SITE("multi_site"),
    VISITOR_CODE_LOOKUP("visitor_code_lookup"),
    ANNOUNCE_VISITOR_NAME("announce_visitor_name");

    private final String wire;

    FeatureFlagKey(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    static Optional<FeatureFlagKey> tryFromWire(String wire) {
        return Arrays.stream(values()).filter(key -> key.wire.equals(wire)).findFirst();
    }
}
