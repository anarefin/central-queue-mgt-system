package com.qms.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

record MeResponse(
        UUID id,
        String username,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("preferred_language") String preferredLanguage,
        List<String> roles,
        List<UUID> sites,
        List<UUID> groups) {}
