package com.qms.configuration.site;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * A waiting area within a site, labelled by building (optional) and floor (FR-CFG-003, ADR-0002). Also carries its
 * voice-announcement settings (ticket 29, SRS §12.3-12.4): the chime and its volume (FR-DSP-025), an optional daily
 * quiet period during which audio is suppressed but the display still updates (FR-DSP-027), the languages
 * announcements play in and their order (FR-DSP-023), and the most announcements ever queued at once (FR-DSP-026).
 */
public record Zone(
        UUID id,
        @JsonProperty("site_id") UUID siteId,
        String name,
        @JsonProperty("building_label") String buildingLabel,
        @JsonProperty("floor_label") String floorLabel,
        @JsonProperty("display_order") int displayOrder,
        boolean active,
        String chime,
        @JsonProperty("chime_volume") int chimeVolume,
        @JsonProperty("quiet_start") LocalTime quietStart,
        @JsonProperty("quiet_end") LocalTime quietEnd,
        @JsonProperty("announcement_languages") List<String> announcementLanguages,
        @JsonProperty("max_announce_queue_depth") int maxAnnounceQueueDepth,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
