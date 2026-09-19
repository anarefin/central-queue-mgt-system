package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * A device as the central health view shows it (FR-OPS-041): identity, pairing, the last heartbeat and version, and
 * a server-computed {@code connectivity} of {@code online}, {@code stale} or {@code offline} so every client agrees
 * on what "healthy" means without repeating the threshold.
 */
record DeviceView(
        UUID id,
        String kind,
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("zone_id") UUID zoneId,
        String label,
        boolean active,
        @JsonProperty("paired_at") Instant pairedAt,
        @JsonProperty("last_heartbeat_at") Instant lastHeartbeatAt,
        @JsonProperty("last_app_version") String lastAppVersion,
        String connectivity) {}
