package com.qms.identity;

import java.time.Instant;
import java.util.UUID;

/** A staff user. Never deleted, only disabled (FR-CFG-104). */
record UserAccount(
        UUID id,
        String username,
        String passwordHash,
        String displayName,
        String preferredLanguage,
        boolean active,
        int failedAttempts,
        Instant lockedUntil,
        Instant passwordChangedAt,
        Instant createdAt) {

    boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
