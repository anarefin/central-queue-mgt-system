package com.qms.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A user as the administration API shows them. Never carries the password hash. */
public record UserView(
        UUID id,
        String username,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("preferred_language") String preferredLanguage,
        boolean active,
        List<RoleView> roles,
        @JsonProperty("created_at") Instant createdAt) {

    public record RoleView(String role, @JsonProperty("site_ids") Set<UUID> siteIds, @JsonProperty("group_ids") Set<UUID> groupIds) {}
}
