package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /kiosk/groups/{groupId}/agents} (ticket 26, FR-ISS-012): the on-duty Agents of a group's team the kiosk
 * may offer at the individual level. An off-duty Agent is never in this list, since FR-ISS-012 permits selecting one
 * only while on duty; {@code queueLonger} is set when that Agent's own personal queue already has more tickets
 * waiting for them than the group as a whole, so the kiosk can warn the visitor before they pick.
 */
public record KioskAgentOptions(List<Agent> items) {

    public record Agent(
            @JsonProperty("agent_id") UUID agentId,
            String name,
            @JsonProperty("queue_length") int queueLength,
            @JsonProperty("queue_longer_than_group") boolean queueLonger) {}
}
