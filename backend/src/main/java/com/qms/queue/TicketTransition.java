package com.qms.queue;

import java.util.Optional;
import java.util.Set;

/**
 * The ticket transitions a counter session drives (SRS §19.1): {@code waiting → called → serving → completed}, and the two
 * ways out of {@code called} that are not service: Re-announce (no change of state) and Miss (back to waiting, or
 * {@code no_show} past the limit; ADR-0005); and Hold, which parks a serving ticket with its Session binding kept until the
 * same session resumes it, or until an admin force-closes the session and the ticket returns to waiting (ADR-0008). Each names
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
    NO_SHOW("called", "no_show", "ticket.no_show"),
    /** F8: the ticket being served is parked. It stays bound to the session and the counter is free to call next (ADR-0008). */
    HOLD("serving", "held", "ticket.held"),
    /** Only the session that holds the ticket may resume it (ADR-0008). Back in service, so it is announced as {@code ticket.serving}. */
    RESUME("held", "serving", "ticket.serving"),
    /**
     * A force-closed session gives up its tickets: each returns to waiting at the front of its queue (ADR-0008) and its binding is
     * cleared. The ticket's place in the queue changed, so it is announced as {@code ticket.position_changed}.
     */
    RETURN_FROM_CALLED("called", "waiting", "ticket.position_changed"),
    RETURN_FROM_SERVING("serving", "waiting", "ticket.position_changed"),
    RETURN_FROM_HELD("held", "waiting", "ticket.position_changed"),
    /**
     * F7: the ticket being served closes as {@code transferred}, terminal, and the visit continues on a successor ticket with the
     * same token number (ADR-0006, Invariant 4). The binding is cleared with the terminal state.
     */
    TRANSFER("serving", "transferred", "ticket.transferred");

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

    /** The state a ticket closes in when it is cancelled, and the event that says so (SRS §19.1: any active state to {@code cancelled}). */
    public static final String CANCELLED = "cancelled";

    public static final String CANCELLED_EVENT = "ticket.cancelled";

    /** The states in which a ticket is still live: everything but the terminal states. */
    private static final Set<String> ACTIVE = Set.of("remote", "waiting", "paused", "called", "serving", "held");

    /**
     * Cancel is not one of the transitions above because it leaves from every active state, not from one (SRS §19.1). It
     * closes the ticket as {@code cancelled}, which is terminal, so it clears the Session binding like every terminal state
     * (Invariant 2). Empty from a state that has already closed.
     */
    public static Optional<String> cancel(String state) {
        return ACTIVE.contains(state) ? Optional.of(CANCELLED) : Optional.empty();
    }

    /** Only a waiting ticket can be given another Priority class (FR-QUE-012); one being served has left the queue. */
    public static boolean mayReprioritise(String state) {
        return "waiting".equals(state);
    }

    /** The state {@code state} moves to under this transition, or empty when the transition is not allowed from there. */
    public Optional<String> apply(String state) {
        return from.equals(state) ? Optional.of(to) : Optional.empty();
    }

    /** Re-announce is allowed while the ticket's {@code announce_count} is below the repeat limit (FR-DSP-028). */
    public static boolean mayReannounce(int announceCount, int repeatLimit) {
        return announceCount < repeatLimit;
    }

    /** Hold is allowed while the session holds fewer tickets than the hold limit (FR-AGT-013); a limit of 0 switches Hold off. */
    public static boolean mayHold(int heldCount, int holdLimit) {
        return heldCount < holdLimit;
    }

    /** The transition that returns a ticket in {@code state} to waiting when its session is force-closed. */
    public static Optional<TicketTransition> returnFrom(String state) {
        return switch (state) {
            case "called" -> Optional.of(RETURN_FROM_CALLED);
            case "serving" -> Optional.of(RETURN_FROM_SERVING);
            case "held" -> Optional.of(RETURN_FROM_HELD);
            default -> Optional.empty();
        };
    }

    /**
     * What a Miss does to a ticket that has been missed {@code missCount} times before: it returns to waiting, or, when the
     * count would exceed {@code missLimit}, it closes as {@code no_show} (FR-QUE-050).
     */
    public static TicketTransition onMiss(int missCount, int missLimit) {
        return missCount + 1 > missLimit ? NO_SHOW : MISS;
    }
}
