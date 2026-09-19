package com.qms.configuration.site;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Validated by {@link SiteRules}, so a failure names the wire field (for example {@code default_language}). */
record CreateSiteRequest(
        String name,
        String code,
        String timezone,
        String address,
        @JsonProperty("default_language") String defaultLanguage,
        @JsonProperty("enabled_languages") List<String> enabledLanguages) {}
