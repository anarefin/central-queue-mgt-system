package com.qms.configuration.approval;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ApprovalView(
        UUID id,
        String type,
        @JsonProperty("requested_by") UUID requestedBy,
        Map<String, Object> payload,
        String status,
        @JsonProperty("decided_by") UUID decidedBy,
        @JsonProperty("decided_at") Instant decidedAt,
        @JsonProperty("decision_reason") String decisionReason,
        @JsonProperty("created_at") Instant createdAt) {}
