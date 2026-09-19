package com.qms.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** F9: with a {@code break_type_id} the agent starts that break; with none, the break they are on ends (FR-AGT-020, FR-AGT-021). */
public record BreakRequest(@JsonProperty("break_type_id") UUID breakTypeId) {}
