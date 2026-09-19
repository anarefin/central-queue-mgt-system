package com.qms.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/** {@code serviceIds} is the subset of the counter's Services to serve; leave it out to serve them all (FR-AGT-003). */
public record OpenSessionRequest(@JsonProperty("counter_id") UUID counterId, @JsonProperty("service_ids") List<UUID> serviceIds) {}
