package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.UUID;

/** {@code GET /visitors/lookup}: the FR-INT-012 contract fields plus the local id needed to issue a ticket for this visitor. */
public record VisitorLookupResponse(
        UUID id, @JsonProperty("external_code") String externalCode, String name, String category, String phone, Map<String, String> flags) {}
