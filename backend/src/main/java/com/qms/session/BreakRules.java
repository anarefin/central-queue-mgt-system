package com.qms.session;

import java.time.Instant;
import java.util.Optional;

/**
 * What a break may do to a session, without a database (SRS §19.3, FR-AGT-021, FR-AGT-022, FR-AGT-023). A session that is
 * {@code open} may go {@code on_break}, and one that is {@code on_break} may go back to {@code open}; every other state
 * (closing, closed, force-closed) has no break to start or end.
 */
final class BreakRules {

    static final String OPEN = "open";
    static final String ON_BREAK = "on_break";

    private BreakRules() {}

    /** The state a session in {@code state} takes when a break starts, if it may. */
    static Optional<String> start(String state) {
        return OPEN.equals(state) ? Optional.of(ON_BREAK) : Optional.empty();
    }

    /** The state a session in {@code state} takes when its break ends, if it is on one. */
    static Optional<String> end(String state) {
        return ON_BREAK.equals(state) ? Optional.of(OPEN) : Optional.empty();
    }

    /**
     * Whether a ticket in {@code ticketState} stops a break from starting: the ticket in progress must be resolved first
     * (FR-AGT-021). A held ticket is parked, not in progress, so it does not.
     */
    static boolean blocksBreak(String ticketState) {
        return "called".equals(ticketState) || "serving".equals(ticketState);
    }

    /** How long a break lasted, in whole seconds (never negative), for the record and its reports (FR-AGT-022). */
    static int seconds(Instant startedAt, Instant endedAt) {
        long seconds = endedAt.getEpochSecond() - startedAt.getEpochSecond();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, seconds));
    }

    /** A break overran when it lasted longer than its type's maximum; a type with no maximum never overruns (FR-AGT-020). */
    static boolean overran(int seconds, Integer maxMinutes) {
        return maxMinutes != null && seconds > maxMinutes * 60L;
    }

    /** The availability an agent has in a session in {@code state}, or {@code offline} with none (FR-AGT-024). */
    static String availability(String state) {
        if (state == null) return "offline";
        return switch (state) {
            case OPEN -> "available";
            case ON_BREAK -> "on_break";
            case "closing" -> "closing";
            default -> "offline";
        };
    }
}
