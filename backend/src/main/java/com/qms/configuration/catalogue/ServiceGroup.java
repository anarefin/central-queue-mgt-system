package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A department or clinic within a site; it owns a set of services and exactly one team (CONTEXT.md). Names are per
 * language, and {@code missingTranslations} lists the enabled languages of the site that have none yet (FR-I18N-010).
 */
public record ServiceGroup(
        UUID id,
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("name_i18n") Map<String, String> nameI18n,
        @JsonProperty("missing_translations") List<String> missingTranslations,
        @JsonProperty("token_prefix") String tokenPrefix,
        @JsonProperty("display_order") int displayOrder,
        boolean active,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
