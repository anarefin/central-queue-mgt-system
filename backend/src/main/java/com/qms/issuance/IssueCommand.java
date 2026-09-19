package com.qms.issuance;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to issue one ticket, independent of the channel it came through (SRS §8.5). Whoever adapts a channel
 * (reception now; kiosk, mobile and appointment check-in later) authenticates its caller, then builds this.
 * {@code deviceTime} is when the originating device says it happened; it is recorded next to the server's time.
 */
public record IssueCommand(UUID serviceId, String originChannel, UUID actorId, ActorType actorType, Instant deviceTime) {}
