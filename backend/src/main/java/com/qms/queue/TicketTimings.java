package com.qms.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The two durations stored on a ticket when it closes (SRS §18.5). {@code waitSeconds} accrues only while the ticket is
 * {@code waiting} (or {@code remote}), never while it is called or serving (Invariant 1): it is the time from joining the
 * queue to being called. {@code serviceSeconds} is the time from the start of service to its completion.
 */
public record TicketTimings(int waitSeconds, int serviceSeconds) {

    /** Timings of a ticket that waited once, was called, served and is now closed. */
    public static TicketTimings atClosure(Instant queuedAt, Instant calledAt, Instant servedAt, Instant closedAt) {
        return new TicketTimings(seconds(queuedAt, calledAt), seconds(servedAt, closedAt));
    }

    /** Whole seconds between two instants, never negative (a device clock may run slightly behind the server's). */
    public static int seconds(Instant from, Instant to) {
        return (int) Math.max(0, Duration.between(from, to).toSeconds());
    }

    /** One recorded change of a ticket's state: when it happened, the state it left ({@code null} at issue) and the one it entered. */
    public record Change(Instant at, String from, String to) {}

    /**
     * The wait of a ticket that may have been called, missed and called again: the sum of its stints in {@code waiting} or
     * {@code remote}, each from joining or re-joining the queue to leaving it (Invariant 1). The first stint starts at
     * {@code queuedAt}; a return starts the next one at the moment of the return. Time spent called, serving or paused is
     * never wait. A stint still open at the end of {@code changes} is not counted: the ticket is closing, not waiting.
     */
    public static int accruedWait(Instant queuedAt, List<Change> changes) {
        int total = 0;
        Instant since = null;
        for (Change change : changes) {
            if (since != null && isWaiting(change.from())) total += seconds(since, change.at());
            since = isWaiting(change.to()) ? (change.from() == null ? queuedAt : change.at()) : null;
        }
        return total;
    }

    private static boolean isWaiting(String state) {
        return "waiting".equals(state) || "remote".equals(state);
    }
}
