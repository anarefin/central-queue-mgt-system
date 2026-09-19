package com.qms.platform.security;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Scope claims and client-supplied ids (FR-CFG-106). A claim set is what the token carries in {@code sites} or
 * {@code groups}; an empty set means the principal is not restricted on that dimension. A client-supplied id is never
 * trusted directly: it is intersected with, or checked against, the claim.
 */
public final class Scope {

    private Scope() {}

    /**
     * The ids the principal may act on for a filter or listing. No requested ids means "everything I am allowed":
     * the whole claim for a scoped principal, nothing extra for an unrestricted one.
     */
    public static Set<UUID> intersect(Set<UUID> claim, Collection<UUID> requested) {
        boolean nothingRequested = requested == null || requested.isEmpty();
        if (claim.isEmpty()) {
            return nothingRequested ? Set.of() : Set.copyOf(requested);
        }
        if (nothingRequested) {
            return Set.copyOf(claim);
        }
        Set<UUID> kept = new HashSet<>(requested);
        kept.retainAll(claim);
        return kept;
    }

    /** For an action on one named site or service group: outside the claim is {@code forbidden}. */
    public static void require(Set<UUID> claim, UUID id) {
        if (id == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "scope_id"));
        }
        if (!claim.isEmpty() && !claim.contains(id)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }
}
