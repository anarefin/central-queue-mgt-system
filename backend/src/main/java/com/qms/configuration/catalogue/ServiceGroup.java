package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A department or clinic within a site; it owns a set of services and exactly one team (CONTEXT.md). Names are per
 * language, and {@code missingTranslations} lists the enabled languages of the site that have none yet (FR-I18N-010).
 *
 * <p>{@code teamSelectable} and {@code individualSelectable} are two of the kiosk selection tree's five levels
 * (ticket 26, FR-ISS-010, FR-ISS-011); the group and service levels always apply and need no flag. Because a group
 * has exactly one team, the team level is never its own screen (single-option steps are skipped automatically,
 * SRS §8.2); {@code teamSelectable} instead gates whether {@code individualSelectable} may offer the team's on-duty
 * agents at all. {@code customLevelNameI18n} and {@code customLevelOptions} are the fifth level, defined per group;
 * the level is enabled exactly when {@code customLevelOptions} is non-empty.
 */
public record ServiceGroup(
        UUID id,
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("name_i18n") Map<String, String> nameI18n,
        @JsonProperty("missing_translations") List<String> missingTranslations,
        @JsonProperty("token_prefix") String tokenPrefix,
        @JsonProperty("display_order") int displayOrder,
        boolean active,
        @JsonProperty("team_selectable") boolean teamSelectable,
        @JsonProperty("individual_selectable") boolean individualSelectable,
        @JsonProperty("custom_level_name_i18n") Map<String, String> customLevelNameI18n,
        @JsonProperty("custom_level_options") List<CustomLevelOption> customLevelOptions,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {

    /** One option of a group's custom kiosk-selection level; {@code id} is the opaque value recorded on a ticket. */
    public record CustomLevelOption(String id, @JsonProperty("name_i18n") Map<String, String> nameI18n) {}
}
