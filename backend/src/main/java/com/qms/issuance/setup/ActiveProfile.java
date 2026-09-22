package com.qms.issuance.setup;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** The vertical profile currently applied to this installation (CFG-002), and when it was applied or last reset. */
public record ActiveProfile(String id, @JsonProperty("applied_at") Instant appliedAt, @JsonProperty("applied_by") String appliedBy) {}
