package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qms.platform.security.Role;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A paired kiosk or display (SRS §5.1). {@code kind} is always {@link Role#KIOSK} or {@link Role#DISPLAY}. A kiosk is
 * scoped to a site with {@code zoneId} null; a display is scoped to one zone within that site (§20.2). Never deleted,
 * only deactivated ("revoked"), like every other physical asset in this schema (FR-CFG-001 precedent).
 *
 * <p>The display-board fields (ticket 28, FR-DSP-001, FR-DSP-002) are meaningless for a kiosk and simply keep their
 * defaults there, the same way {@code zoneId} is null and unused for one. {@code layout} is one of the shipped set
 * ({@code now_serving_table} is the only one this build implements; ticket 30 adds the rest). {@code assignmentScope}
 * is {@code zone}, {@code counters} or {@code queues}; {@code assignmentIds} is empty for {@code zone} and a list of
 * Counter or Service ids otherwise.
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
        @JsonProperty("language_cycle") List<String> languageCycle,
        @JsonProperty("next_n") int nextN,
        @JsonProperty("highlight_seconds") int highlightSeconds,
        List<String> columns,
        @JsonProperty("assignment_scope") String assignmentScope,
        @JsonProperty("assignment_ids") List<UUID> assignmentIds,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
