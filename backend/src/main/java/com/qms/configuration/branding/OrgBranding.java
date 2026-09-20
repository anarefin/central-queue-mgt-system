package com.qms.configuration.branding;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * The organisation's logo, primary colour and name (FR-CFG-030), applied to kiosk, display, printed token, mobile
 * app and reports. Single-tenant: there is exactly one of these. {@code logoUrl} is null until an admin sets one
 * (an absolute URL or a {@code data:} URI both render fine as an {@code <img src>} on every surface, so the API
 * does not care which); every surface treats a null logo as "no logo" rather than an error.
 */
public record OrgBranding(
        @JsonProperty("org_name") String orgName,
        @JsonProperty("primary_color") String primaryColor,
        @JsonProperty("logo_url") String logoUrl,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("updated_by") UUID updatedBy) {

    static final OrgBranding DEFAULT = new OrgBranding("QMS", "#0b5fff", null, null, null);
}
