package com.qms.queue;

import java.util.Optional;

/**
 * The ticket transitions a counter session drives (SRS §19.1): {@code waiting → called → serving → completed}. Each names
 * the state it leaves, the state it enters and the event it writes (Invariant 3). Pure, so the engine test suite can walk
 * every transition and every refusal without a database (NFR-MNT-004).
 */
public enum TicketTransition {
    /** An agent's call, or the engine's pick for a "call next". */
    CALL("waiting", "called", "ticket.called"),
    START_SERVICE("called", "serving", "ticket.serving"),
    COMPLETE("serving", "completed", "ticket.completed");

    private final String from;
    private final String to;
    private final String eventType;

    TicketTransition(String from, String to, String eventType) {
        this.from = from;
        this.to = to;
        this.eventType = eventType;
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }

    public String eventType() {
        return eventType;
    }

    /** The state {@code state} moves to under this transition, or empty when the transition is not allowed from there. */
    public Optional<String> apply(String state) {
        return from.equals(state) ? Optional.of(to) : Optional.empty();
    }
}
