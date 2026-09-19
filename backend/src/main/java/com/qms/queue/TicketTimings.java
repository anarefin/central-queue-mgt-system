package com.qms.queue;

import java.time.Duration;
import java.time.Instant;

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
}
