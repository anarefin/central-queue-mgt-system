package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qms.platform.security.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A paired kiosk or display (SRS §5.1). {@code kind} is always {@link Role#KIOSK} or {@link Role#DISPLAY}. A kiosk is
 * scoped to a site with {@code zoneId} null; a display is scoped to one zone within that site (§20.2). Never deleted,
 * only deactivated ("revoked"), like every other physical asset in this schema (FR-CFG-001 precedent).
 *
 * <p>The display-board fields (ticket 28, FR-DSP-001, FR-DSP-002) are meaningless for a kiosk and simply keep their
 * defaults there, the same way {@code zoneId} is null and unused for one. {@code layout} is one of the shipped set
 * (ticket 30 adds {@code split_media}, {@code single_counter} and {@code summary_board} to ticket 28's
 * {@code now_serving_table}); {@code layoutConfig} is the zone-proportion setting the chosen layout needs, if any
 * (FR-DSP-003). {@code assignmentScope} is {@code zone}, {@code counters} or {@code queues}; {@code assignmentIds} is
 * empty for {@code zone} and a list of Counter or Service ids otherwise. {@code languageCycleSeconds} is how often
 * the display rotates through {@code languageCycle} (ticket 30, FR-I18N-005); 0 renders the cycle side by side.
 */
public record Device(
        UUID id,
        Role kind,
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("zone_id") UUID zoneId,
        String label,
        boolean active,
        @JsonProperty("paired_at") Instant pairedAt,
        @JsonProperty("last_heartbeat_at") Instant lastHeartbeatAt,
        @JsonProperty("last_app_version") String lastAppVersion,
        String layout,
        @JsonProperty("layout_config") Map<String, Object> layoutConfig,
        @JsonProperty("language_cycle") List<String> languageCycle,
        @JsonProperty("language_cycle_seconds") int languageCycleSeconds,
        @JsonProperty("next_n") int nextN,
        @JsonProperty("highlight_seconds") int highlightSeconds,
        List<String> columns,
        @JsonProperty("assignment_scope") String assignmentScope,
        @JsonProperty("assignment_ids") List<UUID> assignmentIds,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
