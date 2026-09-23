package com.qms.configuration.branding;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The non-sensitive subset of {@link OrgBranding} a login screen or the anonymous visitor page can read before
 * authenticating (FR-CFG-030, ticket 62): no {@code updated_at}/{@code updated_by}, which name a staff user.
 */
public record BrandingTheme(@JsonProperty("org_name") String orgName, @JsonProperty("primary_color") String primaryColor, @JsonProperty("logo_url") String logoUrl) {

    static BrandingTheme from(OrgBranding branding) {
        return new BrandingTheme(branding.orgName(), branding.primaryColor(), branding.logoUrl());
    }
}
