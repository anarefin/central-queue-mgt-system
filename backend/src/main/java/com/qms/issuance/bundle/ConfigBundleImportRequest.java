package com.qms.issuance.bundle;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The body of {@code POST /config/bundle/import}: exactly what {@code GET /config/bundle} returned (CFG-004). */
public record ConfigBundleImportRequest(@JsonProperty("payload_json") String payloadJson, String signature) {}
