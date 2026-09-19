package com.qms.queue;

/**
 * Where a missed ticket re-enters its queue (FR-QUE-051): at the front, after a configured number of tickets, or at the
 * back. It is applied as a Score adjustment; the ticket's {@code queued_at} is never rewritten (ADR-0004).
 */
public enum ReentryPosition {
    FRONT,
    AFTER_N,
    BACK
}
