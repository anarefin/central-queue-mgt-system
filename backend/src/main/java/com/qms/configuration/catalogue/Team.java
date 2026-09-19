package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The agents who can serve a service group; one per group, created with it (CONTEXT.md). */
public record Team(
        UUID id,
        @JsonProperty("service_group_id") UUID serviceGroupId,
        String name,
        List<Member> members) {

    public record Member(
            @JsonProperty("user_id") UUID userId,
            String username,
            @JsonProperty("display_name") String displayName,
            boolean active,
            @JsonProperty("added_at") Instant addedAt) {}
}
