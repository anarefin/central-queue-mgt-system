package com.qms.queue;

import java.util.UUID;

/**
 * What a transfer decides that needs no database (SRS §10.6, ADR-0006), so the engine suite can walk it without one (NFR-MNT-004).
 * A transfer closes the serving ticket as {@code transferred} and opens a successor that keeps the visitor's place: the successor's
 * own wait starts at the transfer, and a transfer Head start is added so the visitor is not sent to the back (FR-QUE-053).
 */
public final class TransferRules {

    private static final double SECONDS_PER_MINUTE = 60.0;

    private TransferRules() {}

    /**
     * The successor's transfer Head start in whole minutes, applied as its Score adjustment. {@code configuredMinutes} is
     * {@code qms.queue.transfer-headstart-minutes}: when it is not set the Head start equals the predecessor's accrued wait
     * (FR-QUE-053), rounded to the nearest minute because a ticket stores whole minutes.
     */
    public static int headStartMinutes(Integer configuredMinutes, int predecessorWaitSeconds) {
        if (configuredMinutes != null) return configuredMinutes;
        return (int) Math.round(Math.max(0, predecessorWaitSeconds) / SECONDS_PER_MINUTE);
    }

    /**
     * Who a successor is meant for beyond its Service: a specific Counter, a specific Agent, or anyone who serves the Service
     * (both null). A ticket targeted at an Agent waits in that Agent's personal queue and no other Counter draws it (FR-QUE-003);
     * one targeted at a Counter is drawn by that Counter alone. An admin's reassignment clears or changes the target.
     */
    public record Target(UUID counterId, UUID agentId) {

        public static final Target ANYONE = new Target(null, null);

        /** Whether the Counter and Agent of a session may draw a ticket with this target. */
        public boolean drawableBy(UUID sessionCounterId, UUID sessionAgentId) {
            return (counterId == null || counterId.equals(sessionCounterId)) && (agentId == null || agentId.equals(sessionAgentId));
        }
    }
}
