package com.qms.audit;

import java.util.Map;
import java.util.UUID;

/**
 * Something that must be written to the audit log. The actor, source address, device and trace id come from the
 * current request and token unless an explicit actor is given (sign-in events happen before there is a token).
 * {@code before} and {@code after} are scrubbed of secrets on the way in.
 */
public record AuditEvent(
        String action,
        String entity,
        UUID entityId,
        Map<String, Object> before,
        Map<String, Object> after,
        String reason,
        UUID actorId,
        String actorRole) {

    public static AuditEvent of(String action, String entity, UUID entityId) {
        return new AuditEvent(action, entity, entityId, null, null, null, null, null);
    }

    public AuditEvent withBefore(Map<String, Object> before) {
        return new AuditEvent(action, entity, entityId, before, after, reason, actorId, actorRole);
    }

    public AuditEvent withAfter(Map<String, Object> after) {
        return new AuditEvent(action, entity, entityId, before, after, reason, actorId, actorRole);
    }

    public AuditEvent withReason(String reason) {
        return new AuditEvent(action, entity, entityId, before, after, reason, actorId, actorRole);
    }

    public AuditEvent withActor(UUID actorId, String actorRole) {
        return new AuditEvent(action, entity, entityId, before, after, reason, actorId, actorRole);
    }
}
