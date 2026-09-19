package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** The raw code is shown to the administrator exactly once; only its hash is ever stored (FR-OPS-011). */
record PairingCodeResponse(String code, @JsonProperty("expires_at") Instant expiresAt) {}
