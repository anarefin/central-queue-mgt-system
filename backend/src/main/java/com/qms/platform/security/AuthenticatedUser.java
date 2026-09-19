package com.qms.platform.security;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The caller as described by a verified access token (API-011): who, which roles, and where a scoped role applies.
 * Empty {@code siteIds} / {@code groupIds} mean the principal is not restricted on that dimension.
 */
public record AuthenticatedUser(UUID userId, Set<Role> roles, Set<UUID> siteIds, Set<UUID> groupIds) {

    public static AuthenticatedUser from(Jwt jwt) {
        return new AuthenticatedUser(
                UUID.fromString(jwt.getSubject()),
                strings(jwt, "roles").stream()
                        .map(Role::tryFromWire)
                        .flatMap(Optional::stream)
                        .collect(Collectors.toUnmodifiableSet()),
                uuids(jwt, "sites"),
                uuids(jwt, "groups"));
    }

    /** Comma-joined wire names, for the audit log's {@code actor_role}. */
    public String rolesCsv() {
        return roles.stream().map(Role::wire).sorted().collect(Collectors.joining(","));
    }

    private static List<String> strings(Jwt jwt, String claim) {
        List<String> values = jwt.getClaimAsStringList(claim);
        return values == null ? List.of() : values;
    }

    private static Set<UUID> uuids(Jwt jwt, String claim) {
        return strings(jwt, claim).stream().map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
    }

    public Collection<String> roleNames() {
        return roles.stream().map(Role::wire).sorted().toList();
    }
}
