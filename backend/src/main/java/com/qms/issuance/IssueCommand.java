package com.qms.issuance;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to issue one ticket, independent of the channel it came through (SRS §8.5). Whoever adapts a channel
 * (reception now; kiosk, mobile and appointment check-in later) authenticates its caller, then builds this.
 * {@code deviceTime} is when the originating device says it happened; it is recorded next to the server's time.
 * {@code priorityClassId} is the class staff chose (FR-QUE-011), or null for the default class. {@code visitorId} is the
 * visitor the ticket is for, when the channel knows them; only then can a duplicate be detected (FR-ISS-004), and
 * {@code confirmDuplicate} is the caller's answer to the warning that the visitor already has an active ticket.
 * {@code purposeNote} is the free-text note Reception adds, visible only to the agent who is called to it (FR-ISS-020).
 */
public record IssueCommand(
        UUID serviceId,
        String originChannel,
        UUID actorId,
        ActorType actorType,
        Instant deviceTime,
        UUID priorityClassId,
        UUID visitorId,
        boolean confirmDuplicate,
        String purposeNote) {

    /** A ticket of the class staff chose, for nobody in particular. */
    public IssueCommand(UUID serviceId, String originChannel, UUID actorId, ActorType actorType, Instant deviceTime, UUID priorityClassId) {
        this(serviceId, originChannel, actorId, actorType, deviceTime, priorityClassId, null, false, null);
    }

    /** A ticket of the default class. */
    public IssueCommand(UUID serviceId, String originChannel, UUID actorId, ActorType actorType, Instant deviceTime) {
        this(serviceId, originChannel, actorId, actorType, deviceTime, null);
    }
}
