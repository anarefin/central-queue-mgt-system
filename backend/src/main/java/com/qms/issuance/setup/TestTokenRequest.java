package com.qms.issuance.setup;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** Which Service the setup wizard's test token is issued for (FR-OPS-010). */
public record TestTokenRequest(@JsonProperty("service_id") UUID serviceId) {}
