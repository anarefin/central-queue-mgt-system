package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A device as the central health view shows it (FR-OPS-041): identity, pairing, the last heartbeat and version, a
 * server-computed {@code connectivity} of {@code online}, {@code stale} or {@code offline} so every client agrees on
 * what "healthy" means without repeating the threshold, and, for a display, its board configuration (ticket 28,
 * FR-DSP-001, FR-DSP-002) so the fleet screen can show and edit it without a second round trip. Meaningless and just
 * this build's defaults for a kiosk, the same way {@code zoneId} is null and unused for one.
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
        String connectivity,
        String layout,
        @JsonProperty("layout_config") Map<String, Object> layoutConfig,
        @JsonProperty("language_cycle") List<String> languageCycle,
        @JsonProperty("language_cycle_seconds") int languageCycleSeconds,
        @JsonProperty("next_n") int nextN,
        @JsonProperty("highlight_seconds") int highlightSeconds,
        List<String> columns,
        @JsonProperty("assignment_scope") String assignmentScope,
        @JsonProperty("assignment_ids") List<UUID> assignmentIds) {}
