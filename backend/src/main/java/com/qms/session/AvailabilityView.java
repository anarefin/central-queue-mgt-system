package com.qms.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * An agent's availability (FR-AGT-024): {@code available} in an open session, {@code on_break} (with the break), {@code closing},
 * or {@code offline} with no live session. A session is what makes an agent available, so there is nothing to set for one who
 * has none.
 */
public record AvailabilityView(
        @JsonProperty("agent_id") UUID agentId,
        @JsonProperty("agent_name") String agentName,
        String status,
        @JsonProperty("session_id") UUID sessionId,
        SessionResponse.CounterRef counter,
        @JsonProperty("break") SessionResponse.Break onBreak) {

    /** The agents with a live session inside the caller's scope. */
    public record Items(List<AvailabilityView> items) {}
}
