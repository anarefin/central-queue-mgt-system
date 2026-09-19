package com.qms.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * @param action exact action, or a prefix ending in {@code *} (for example {@code auth.*})
 * @param from   inclusive lower bound on {@code occurred_at}
 * @param to     exclusive upper bound on {@code occurred_at}
 */
public record AuditFilter(
        UUID actorId, String action, String entity, UUID entityId, OffsetDateTime from, OffsetDateTime to) {}
