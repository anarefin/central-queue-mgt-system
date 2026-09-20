package com.qms.configuration.site;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Validated by {@link SiteRules}, so a failure names the wire field. Audio fields not given take this build's defaults (ticket 29).
 * {@code wayfinding_image_url} left out or blank means no image (FR-MOB-032, ticket 37). */
record CreateZoneRequest(
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
