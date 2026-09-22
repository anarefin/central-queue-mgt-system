package com.qms.issuance.bundle;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * A signed configuration bundle (CFG-004): {@code payloadJson} is the exact canonical JSON text that was signed,
 * carried as a string rather than a parsed object so the signature can be verified byte-for-byte on the way back in,
 * with no risk of a re-serialisation changing so much as one character.
 */
public record ConfigBundleResponse(@JsonProperty("exported_at") Instant exportedAt, @JsonProperty("payload_json") String payloadJson, String signature) {}
