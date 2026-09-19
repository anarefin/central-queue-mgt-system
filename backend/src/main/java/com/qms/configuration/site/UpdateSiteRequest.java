package com.qms.configuration.site;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Absent (null) fields are left unchanged. Validated by {@link SiteRules}. */
record UpdateSiteRequest(
        String name,
        String code,
        String timezone,
        String address,
        @JsonProperty("default_language") String defaultLanguage,
        @JsonProperty("enabled_languages") List<String> enabledLanguages) {}
