package com.qms.platform.realtime;

import java.util.UUID;

/** The topic names of SRS §21.2 that have a publisher so far. */
public final class Topics {

    public static final String QUEUE = "queue:";
    public static final String COUNTER = "counter:";

    private Topics() {}

    public static String queue(UUID serviceId) {
        return QUEUE + serviceId;
    }

    public static String counter(UUID counterId) {
        return COUNTER + counterId;
    }
}
