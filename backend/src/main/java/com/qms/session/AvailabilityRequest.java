package com.qms.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * What a Team or Org Admin sets an agent's availability to (FR-AGT-024): {@code on_break}, which needs a
 * {@code break_type_id}, or {@code available}, which ends the break. The {@code reason} is kept in the audit entry.
 */
public record AvailabilityRequest(String status, @JsonProperty("break_type_id") UUID breakTypeId, String reason) {}
