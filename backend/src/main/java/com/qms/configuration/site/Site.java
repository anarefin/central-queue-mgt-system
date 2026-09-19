package com.qms.configuration.site;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The operational unit (ADR-0002). Timestamps are UTC instants; {@code timezone} says how to render them (FR-CFG-002).
 * An inactive site still resolves by id, so historical records keep pointing at it (FR-CFG-001).
 */
public record Site(
        UUID id,
        String name,
        String code,
        String timezone,
        String address,
        @JsonProperty("default_language") String defaultLanguage,
        @JsonProperty("enabled_languages") List<String> enabledLanguages,
        boolean active,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
