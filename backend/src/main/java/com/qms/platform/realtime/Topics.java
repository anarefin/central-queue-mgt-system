package com.qms.platform.realtime;

import java.util.UUID;

/** The topic names of SRS §21.2 that have a publisher so far. */
public final class Topics {

    public static final String QUEUE = "queue:";
    public static final String COUNTER = "counter:";
    public static final String DEVICE = "device:";
    public static final String ZONE = "zone:";

    private Topics() {}

    public static String queue(UUID serviceId) {
        return QUEUE + serviceId;
    }

    public static String counter(UUID counterId) {
        return COUNTER + counterId;
    }

    /** Config change, reload and revoke commands for one device (§21.2, FR-OPS-042). */
    public static String device(UUID deviceId) {
        return DEVICE + deviceId;
    }

    /** A zone's display board(s): the serving table and next-token strip of every counter in it (§21.2, ticket 28, FR-DSP-010). */
    public static String zone(UUID zoneId) {
        return ZONE + zoneId;
    }
}
