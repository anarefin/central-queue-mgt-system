package com.qms.queue;

import java.time.Instant;
import java.util.Collection;

/**
 * What a call decides that needs no database (SRS §10.3, §11.2), so the engine suite can walk it without one (NFR-MNT-004): how
 * many tickets a Counter may have in progress at once when its Services allow parallel serving (FR-AGT-010, FR-AGT-011), and when
 * a called ticket that nobody acts on has timed out (FR-QUE-032).
 */
public final class CallRules {

    private CallRules() {}

    /** The most tickets a Service lets a Counter have in progress: its configured maximum when it is parallel, else one (FR-AGT-011). */
    public static int limit(boolean parallelServing, int maxConcurrent) {
        return parallelServing ? Math.max(1, maxConcurrent) : 1;
    }

    /**
     * Whether a Counter with {@code inProgress} tickets called or serving may take another, given the {@link #limit} of the Service
     * of each of those tickets and of the one it would take. Every one of them must have room: a Counter busy with a Service that is
     * not parallel takes no second ticket, whatever the new one's Service allows (FR-AGT-010).
     */
    public static boolean mayTakeAnother(int inProgress, Collection<Integer> limits) {
        return limits.stream().allMatch(limit -> inProgress < limit);
    }

    /** A called ticket has timed out once {@code timeoutSeconds} have passed since the call; 0 switches the timeout off (FR-QUE-032). */
    public static boolean callTimedOut(Instant calledAt, Instant now, int timeoutSeconds) {
        return timeoutSeconds > 0 && calledAt != null && !now.isBefore(calledAt.plusSeconds(timeoutSeconds));
    }
}
