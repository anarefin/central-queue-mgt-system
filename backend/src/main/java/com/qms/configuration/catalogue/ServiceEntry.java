package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The thing a visitor queues for (FR-CFG-010, FR-CFG-012..014). {@code siteId} is derived from the service group.
 * {@code visitorIdentifier} is {@code not_required}, {@code optional} or {@code mandatory}; {@code bookingMode} is
 * {@code appointment_only}, {@code walk_in_only} or {@code both}. A Service with {@code parallelServing} lets a counter have up to
 * {@code parallelLimit} of its tickets called or serving at once (FR-AGT-010, FR-AGT-011); otherwise a counter serves one at a time.
 */
public record ServiceEntry(
        UUID id,
        @JsonProperty("service_group_id") UUID serviceGroupId,
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("name_i18n") Map<String, String> nameI18n,
        @JsonProperty("missing_translations") List<String> missingTranslations,
        @JsonProperty("token_prefix") String tokenPrefix,
        /** Enabled languages of this Service's site with no spoken form yet for {@code token_prefix} (FR-I18N-041); an admin prompt, never a block. */
        @JsonProperty("missing_spoken_forms") List<String> missingSpokenForms,
        @JsonProperty("expected_minutes") int expectedMinutes,
        @JsonProperty("sla_wait_minutes") int slaWaitMinutes,
        List<String> channels,
        String icon,
        @JsonProperty("display_order") int displayOrder,
        @JsonProperty("visitor_identifier") String visitorIdentifier,
        @JsonProperty("booking_mode") String bookingMode,
        @JsonProperty("parallel_serving") boolean parallelServing,
        @JsonProperty("parallel_limit") int parallelLimit,
        /** Whether a call for this Service speaks the visitor's name (FR-DSP-022), default off: inappropriate in medical settings. */
        @JsonProperty("announce_visitor_name") boolean announceVisitorName,
        boolean active,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
