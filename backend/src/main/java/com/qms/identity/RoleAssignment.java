package com.qms.identity;

import com.qms.platform.security.Role;
import java.util.Set;
import java.util.UUID;

/**
 * One role held by a user, with the sites and service groups it covers (SRS §5). Both sets empty means the assignment
 * is organisation-wide.
 */
public record RoleAssignment(Role role, Set<UUID> siteIds, Set<UUID> groupIds) {

    public RoleAssignment {
        siteIds = Set.copyOf(siteIds);
        groupIds = Set.copyOf(groupIds);
    }

    public boolean isOrganisationWide() {
        return siteIds.isEmpty() && groupIds.isEmpty();
    }
}
