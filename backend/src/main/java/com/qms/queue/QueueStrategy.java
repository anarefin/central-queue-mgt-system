package com.qms.queue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** The named ways a Service group can order its waiting tickets (FR-QUE-021). */
public enum QueueStrategy {
    /** Highest score first: {@code effective wait + Head start + appointment bonus + Escalation bonus + Score adjustment} (ADR-0003). */
    WEIGHTED_WAIT("weighted_wait"),
    /** Escalated tickets first, then the class with the larger Head start, then creation order. */
    STRICT_PRIORITY("strict_priority"),
    /** Creation order only. */
    FIFO("fifo");

    /** Used by a Service group that has not chosen one. */
    public static final QueueStrategy DEFAULT = WEIGHTED_WAIT;

    private final String wire;

    QueueStrategy(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<QueueStrategy> fromWire(String wire) {
        return Arrays.stream(values()).filter(s -> s.wire.equals(wire)).findFirst();
    }

    public static List<String> wires() {
        return Arrays.stream(values()).map(QueueStrategy::wire).toList();
    }
}
