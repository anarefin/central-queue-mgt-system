package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Body of {@code GET /devices/{id}/display-state} (ticket 28, FR-DSP-012): everything a display needs to resume its
 * assigned zone and layout after a power loss, with no login, in one round trip -- its own configuration plus a
 * fresh snapshot of who is being served where and what is coming up next in the whole zone. {@code serving} and
 * {@code next} are always the whole zone's state (one row per active Counter, every Service the zone's Counters
 * serve): a display narrower than the whole zone (FR-DSP-002's {@code counters}/{@code queues} assignment) filters
 * this down to its own {@code assignment} itself, the same filter it applies to every {@code zone:} topic update
 * afterwards, so there is exactly one place that logic lives.
 */
record DisplayStateResponse(
        ZoneRef zone,
        String layout,
        @JsonProperty("layout_config") Map<String, Object> layoutConfig,
        @JsonProperty("language_cycle") List<String> languageCycle,
        @JsonProperty("language_cycle_seconds") int languageCycleSeconds,
        List<String> columns,
        @JsonProperty("next_n") int nextN,
        @JsonProperty("highlight_seconds") int highlightSeconds,
        Assignment assignment,
        List<ServingEntry> serving,
        List<NextGroupEntry> next,
        /** `split_media`'s notice panel (FR-DSP-006): the zone's scheduled notices active right now, in playlist order. */
        List<NoticeEntry> notices,
        /** `summary_board`'s per-Service lobby summary (FR-DSP-003): waiting count and estimated wait. */
        List<SummaryEntry> summary) {

    /**
     * {@code chime}..{@code max_announce_queue_depth} are the zone's voice-announcement settings (ticket 29, FR-DSP-023,
     * FR-DSP-025..027): quiet hours suppress audio only, never this read or the {@code zone:} topic itself.
     */
    record ZoneRef(
            UUID id,
            String name,
            @JsonProperty("building_label") String buildingLabel,
            @JsonProperty("floor_label") String floorLabel,
            String chime,
            @JsonProperty("chime_volume") int chimeVolume,
            @JsonProperty("quiet_start") String quietStart,
            @JsonProperty("quiet_end") String quietEnd,
            @JsonProperty("announcement_languages") List<String> announcementLanguages,
            @JsonProperty("max_announce_queue_depth") int maxAnnounceQueueDepth) {}

    record Assignment(String scope, List<UUID> ids) {}

    /**
     * {@code token_prefix_spoken} (ticket 29, FR-DSP-030) is the spoken form of {@code token_prefix} per language,
     * empty until an admin records one (FR-I18N-041); {@code announce_visitor_name} is the calling Service's flag
     * (FR-DSP-022). Both are null-safe defaults (no service, no spoken forms yet) rather than absent.
     */
    record ServingEntry(
            @JsonProperty("counter_id") UUID counterId,
            @JsonProperty("counter_label") String counterLabel,
            @JsonProperty("token_number") String tokenNumber,
            String state,
            @JsonProperty("service_id") UUID serviceId,
            @JsonProperty("service_names") Map<String, String> serviceNames,
            @JsonProperty("staff_name") String staffName,
            @JsonProperty("token_prefix") String tokenPrefix,
            @JsonProperty("token_prefix_spoken") Map<String, String> tokenPrefixSpoken,
            @JsonProperty("announce_visitor_name") boolean announceVisitorName) {}

    record NextGroupEntry(
            @JsonProperty("service_id") UUID serviceId,
            @JsonProperty("service_names") Map<String, String> serviceNames,
            List<NextTicketEntry> tokens) {}

    record NextTicketEntry(@JsonProperty("token_number") String tokenNumber, int position) {}

    /** One scheduled notice-board item (ticket 30, FR-DSP-006), already filtered to zone + active + in its date window. */
    record NoticeEntry(
            UUID id,
            String type,
            @JsonProperty("content_i18n") Map<String, String> contentI18n,
            @JsonProperty("sort_order") int sortOrder) {}

    /** One Service's lobby summary (ticket 30, FR-DSP-003): how many are waiting and the estimated wait range for a
     * visitor joining now (SRS §10.5, FR-QUE-040..042), the same rounded range a kiosk shows, never an exact promise. */
    record SummaryEntry(
            @JsonProperty("service_id") UUID serviceId,
            @JsonProperty("service_names") Map<String, String> serviceNames,
            @JsonProperty("waiting_count") int waitingCount,
            @JsonProperty("estimate_low_minutes") int estimateLowMinutes,
            @JsonProperty("estimate_high_minutes") int estimateHighMinutes) {}
}
