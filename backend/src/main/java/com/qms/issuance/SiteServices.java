package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The services a site offers, with the live queue length of each: what a channel shows before it issues (SRS §20.4). */
public record SiteServices(
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("default_language") String defaultLanguage,
        List<Item> items) {

    public record Item(
            UUID id,
            @JsonProperty("name_i18n") Map<String, String> nameI18n,
            @JsonProperty("service_group") NameRef serviceGroup,
            @JsonProperty("token_prefix") String tokenPrefix,
            String icon,
            @JsonProperty("display_order") int displayOrder,
            @JsonProperty("waiting_count") int waitingCount,
            @JsonProperty("estimated_wait_minutes") EstimatedWait estimatedWait) {}
}
