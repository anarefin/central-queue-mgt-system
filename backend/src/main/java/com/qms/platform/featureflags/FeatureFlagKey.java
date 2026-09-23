package com.qms.platform.featureflags;

import java.util.Arrays;
import java.util.Optional;

/**
 * The closed vocabulary of feature-flag keys a vertical profile turns on or off (CFG-001, SRS §3.3.6): the same six
 * keys every shipped {@code profiles/<wire>.json} carries under {@code feature_flags}. Never an industry branch in
 * code; this only bounds which keys {@code PUT /setup/feature-flags/{key}} (CFG-003) may write.
 *
 * <p>Lives in {@code platform} (ticket 68), not the {@code issuance.setup} package that owns the flag table and its
 * admin screen, so every bounded context that gates a feature can name a key without depending on {@code
 * issuance.setup} itself — the same "own the type, not the context" reasoning {@code
 * com.qms.platform.security.Authorities} already follows for permissions every controller's {@code @PreAuthorize}
 * names, and {@code com.qms.platform.devices.DeviceConfigNotifier} follows for the seam a context pushes a device
 * config change through without depending on the {@code device} package that implements it.
 */
public enum FeatureFlagKey {
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

    public String wire() {
        return wire;
    }

    public static Optional<FeatureFlagKey> tryFromWire(String wire) {
        return Arrays.stream(values()).filter(key -> key.wire.equals(wire)).findFirst();
    }
}
