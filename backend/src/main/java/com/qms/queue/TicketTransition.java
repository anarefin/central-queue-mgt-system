package com.qms.queue;

import java.util.Optional;

/**
 * The ticket transitions a counter session drives (SRS §19.1): {@code waiting → called → serving → completed}, and the two
 * ways out of {@code called} that are not service: Re-announce (no change of state) and Miss (back to waiting, or
 * {@code no_show} past the limit; ADR-0005). Each names
 * the state it leaves, the state it enters and the event it writes (Invariant 3). Pure, so the engine test suite can walk
 * every transition and every refusal without a database (NFR-MNT-004).
 */
public enum TicketTransition {
    /** An agent's call, or the engine's pick for a "call next". */
    CALL("waiting", "called", "ticket.called"),
    START_SERVICE("called", "serving", "ticket.serving"),
    COMPLETE("serving", "completed", "ticket.completed"),
    /** F3: the call is replayed. The ticket stays called and keeps its Session binding (ADR-0005). */
    REANNOUNCE("called", "called", "ticket.reannounced"),
    /** F6: the visitor is absent and the ticket returns to the queue; the binding is cleared (Invariant 2). */
    MISS("called", "waiting", "ticket.missed"),
    /** F6 past the miss limit: the ticket closes. Agents never choose this directly (ADR-0005). */
    NO_SHOW("called", "no_show", "ticket.no_show");

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

    /** Re-announce is allowed while the ticket's {@code announce_count} is below the repeat limit (FR-DSP-028). */
    public static boolean mayReannounce(int announceCount, int repeatLimit) {
        return announceCount < repeatLimit;
    }

    /**
     * What a Miss does to a ticket that has been missed {@code missCount} times before: it returns to waiting, or, when the
     * count would exceed {@code missLimit}, it closes as {@code no_show} (FR-QUE-050).
     */
    public static TicketTransition onMiss(int missCount, int missLimit) {
        return missCount + 1 > missLimit ? NO_SHOW : MISS;
    }
}
