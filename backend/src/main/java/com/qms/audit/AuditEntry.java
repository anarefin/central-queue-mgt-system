package com.qms.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** One row of the audit log as returned by {@code GET /audit}. */
public record AuditEntry(
        UUID id,
        @JsonProperty("actor_id") UUID actorId,
        @JsonProperty("actor_role") String actorRole,
        String action,
        String entity,
        @JsonProperty("entity_id") UUID entityId,
        Map<String, Object> before,
        Map<String, Object> after,
        String ip,
        String device,
        String reason,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("occurred_at") OffsetDateTime occurredAt) {}
