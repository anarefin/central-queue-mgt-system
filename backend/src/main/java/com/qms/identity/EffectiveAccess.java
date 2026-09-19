package com.qms.identity;

import com.qms.platform.security.Role;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What a user's assignments add up to, in the shape the token carries (API-011): the union of roles and, only where
 * the user is scoped, the union of site and group ids. If any assignment is organisation-wide the scope claims are
 * omitted, meaning unrestricted. The token is flat, so per-role scope is not preserved; a user holding two scoped
 * roles is limited to the union of both scopes.
 */
record EffectiveAccess(Set<Role> roles, Set<UUID> siteIds, Set<UUID> groupIds) {

    static EffectiveAccess of(List<RoleAssignment> assignments) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        Set<UUID> sites = new HashSet<>();
        Set<UUID> groups = new HashSet<>();
        boolean unrestricted = false;
        for (RoleAssignment assignment : assignments) {
            roles.add(assignment.role());
            if (assignment.isOrganisationWide()) {
                unrestricted = true;
            }
            sites.addAll(assignment.siteIds());
            groups.addAll(assignment.groupIds());
        }
        return unrestricted
                ? new EffectiveAccess(roles, Set.of(), Set.of())
                : new EffectiveAccess(roles, Set.copyOf(sites), Set.copyOf(groups));
    }
}
