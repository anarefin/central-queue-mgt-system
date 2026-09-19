package com.qms.issuance;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a visitor by an external key: a typed code, a phone number, or whatever a QR scan yields (FR-INT-010). v1
 * ships one implementation, {@link LocalVisitorDirectory}, backed by the local {@code visitor} table; a remote
 * adapter (core banking CIF, HIS patient index, ERP supplier master) is addable later by implementing this same
 * interface, ahead of the local one in the wired list (FR-INT-012). Every implementation MUST answer promptly:
 * {@link VisitorDirectoryGateway} wraps each call in a hard timeout so a slow or unreachable directory can never hold
 * up a caller, and falls through to the next directory instead (FR-INT-013).
 */
public interface VisitorDirectory {

    /** {@code query} is whatever the caller has on hand: a typed code, a phone number, or a scanned QR payload. */
    Optional<Match> lookup(String query);

    /**
     * A directory hit. {@code id}, {@code externalCode}, {@code name}, {@code category}, {@code phone} and
     * {@code flags} are the FR-INT-012 contract; {@code id} is additionally the local {@code visitor} row's id, so a
     * caller can issue a ticket for this visitor straight away. It is always present for {@link LocalVisitorDirectory}
     * because local storage is the row itself; a future remote adapter's match would need mirroring locally first
     * (upsert by {@code externalCode}, FR-INT-011) before a ticket could be issued for it.
     */
    record Match(UUID id, String externalCode, String name, String category, String phone, Map<String, String> flags) {}
}
