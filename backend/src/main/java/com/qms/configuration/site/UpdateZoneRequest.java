package com.qms.configuration.site;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Absent (null) fields are left unchanged; an empty {@code building_label} clears it. Validated by {@link SiteRules}.
 * The audio fields are ticket 29's per-zone voice-announcement settings (FR-DSP-023, FR-DSP-025, FR-DSP-026, FR-DSP-027);
 * {@code quiet_start}/{@code quiet_end} are both cleared by sending an empty string for either. {@code wayfinding_image_url}
 * follows the same convention: absent leaves it unchanged, an empty string clears it (FR-MOB-032, ticket 37).
 */
record UpdateZoneRequest(
        String name,
        @JsonProperty("floor_label") String floorLabel,
        @JsonProperty("building_label") String buildingLabel,
        @JsonProperty("display_order") Integer displayOrder,
        String chime,
        @JsonProperty("chime_volume") Integer chimeVolume,
        @JsonProperty("quiet_start") String quietStart,
        @JsonProperty("quiet_end") String quietEnd,
        @JsonProperty("announcement_languages") List<String> announcementLanguages,
        @JsonProperty("max_announce_queue_depth") Integer maxAnnounceQueueDepth,
        @JsonProperty("wayfinding_image_url") String wayfindingImageUrl) {}
