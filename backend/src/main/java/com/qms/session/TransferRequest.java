package com.qms.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * A transfer (F7, FR-QUE-052). The {@code note} is mandatory. The target is a Service ({@code service_id}), optionally narrowed to
 * one of its Counters ({@code counter_id}) or Agents ({@code agent_id}), not both. A Counter or Agent without a {@code service_id}
 * means the ticket's own Service.
 */
public record TransferRequest(
        @JsonProperty("service_id") UUID serviceId,
        @JsonProperty("counter_id") UUID counterId,
        @JsonProperty("agent_id") UUID agentId,
        String note) {}
