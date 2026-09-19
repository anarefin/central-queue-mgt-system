package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A result an agent can record when a ticket of the service completes (FR-AGT-032, FR-AGT-033). */
public record OutcomeCode(
        UUID id,
        @JsonProperty("service_id") UUID serviceId,
        @JsonProperty("site_id") UUID siteId,
        String code,
        @JsonProperty("label_i18n") Map<String, String> labelI18n,
        @JsonProperty("missing_translations") List<String> missingTranslations,
        @JsonProperty("display_order") int displayOrder,
        boolean active,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
