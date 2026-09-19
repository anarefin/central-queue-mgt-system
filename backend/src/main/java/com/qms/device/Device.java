package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qms.platform.security.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * A paired kiosk or display (SRS §5.1). {@code kind} is always {@link Role#KIOSK} or {@link Role#DISPLAY}. A kiosk is
 * scoped to a site with {@code zoneId} null; a display is scoped to one zone within that site (§20.2). Never deleted,
 * only deactivated ("revoked"), like every other physical asset in this schema (FR-CFG-001 precedent).
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
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
