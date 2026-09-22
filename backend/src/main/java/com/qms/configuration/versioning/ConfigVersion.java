package com.qms.configuration.versioning;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One snapshot of a versioned configuration entity (FR-CFG-040): the whole editable content after a change, who made
 * it and when. {@code entity} is the config table name ({@code priority_class}, {@code routing_strategy},
 * {@code numbering_rule} or {@code business_hours}); {@code entityId} is that area's own scope id (see
 * {@link ConfigVersionRepository}).
 */
public record ConfigVersion(UUID id, String entity, UUID entityId, Map<String, Object> payload, UUID changedBy, Instant changedAt) {}
