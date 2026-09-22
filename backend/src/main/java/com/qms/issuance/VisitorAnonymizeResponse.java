package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** {@code POST /visitors/{id}/anonymize} (FR-SEC-031). */
public record VisitorAnonymizeResponse(UUID id, @JsonProperty("anonymized_at") Instant anonymizedAt, @JsonProperty("tickets_anonymized") int ticketsAnonymized) {}
