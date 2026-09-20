package com.qms.mobile;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** One of a registered visitor's own saved sites (FR-MOB-002). */
public record SavedSiteView(@JsonProperty("site_id") UUID siteId, @JsonProperty("site_name") String siteName) {}
