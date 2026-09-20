package com.qms.configuration.branding;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of {@code PUT /branding} (FR-CFG-030). */
public record BrandingRequest(
        @JsonProperty("org_name") String orgName, @JsonProperty("primary_color") String primaryColor, @JsonProperty("logo_url") String logoUrl) {}
