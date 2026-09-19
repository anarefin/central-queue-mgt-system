package com.qms.configuration.approval;

import java.util.Map;
import java.util.UUID;

/**
 * Published after an Org Admin decides a request. The contexts that own team membership and counter allocation
 * (later tickets) listen for it and apply the change on {@code approved}; nothing takes effect before that.
 */
public record ApprovalDecided(UUID approvalId, ApprovalType type, boolean approved, Map<String, Object> payload) {}
