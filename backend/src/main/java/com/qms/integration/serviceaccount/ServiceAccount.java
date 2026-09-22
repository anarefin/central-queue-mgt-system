package com.qms.integration.serviceaccount;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A client id and its bcrypt-hashed secret (ticket 58, SRS §20.2, §22.4, FR-INT-030): a host system's own machine
 * credential, scoped to the sites named here (never the whole organisation) and exchanged for a JWT carrying
 * {@link com.qms.platform.security.Role#HOST_SYSTEM} — the same shape a device's pairing already is, minus the
 * pairing-code handshake, since a host system holds a long-lived credential of its own instead of being paired once
 * by staff standing in front of it.
 */
record ServiceAccount(
        UUID id, String clientId, String secretHash, String label, Set<UUID> siteIds, boolean active, Instant createdAt, Instant updatedAt) {}
