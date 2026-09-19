package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The thing a visitor queues for (FR-CFG-010, FR-CFG-012..014). {@code siteId} is derived from the service group.
 * {@code visitorIdentifier} is {@code not_required}, {@code optional} or {@code mandatory}; {@code bookingMode} is
 * {@code appointment_only}, {@code walk_in_only} or {@code both}.
 */
public record ServiceEntry(
        UUID id,
        @JsonProperty("service_group_id") UUID serviceGroupId,
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("name_i18n") Map<String, String> nameI18n,
        @JsonProperty("missing_translations") List<String> missingTranslations,
        @JsonProperty("token_prefix") String tokenPrefix,
        @JsonProperty("expected_minutes") int expectedMinutes,
        @JsonProperty("sla_wait_minutes") int slaWaitMinutes,
        List<String> channels,
        String icon,
        @JsonProperty("display_order") int displayOrder,
        @JsonProperty("visitor_identifier") String visitorIdentifier,
        @JsonProperty("booking_mode") String bookingMode,
        boolean active,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
