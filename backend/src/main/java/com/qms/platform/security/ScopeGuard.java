package com.qms.platform.security;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Applies {@link Scope} to the current principal's claims. Call it before using any client-supplied scope id. */
@Component
public class ScopeGuard {

    private final CurrentUser currentUser;

    public ScopeGuard(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    public Set<UUID> sites(Collection<UUID> requested) {
        return Scope.intersect(currentUser.require().siteIds(), requested);
    }

    public Set<UUID> groups(Collection<UUID> requested) {
        return Scope.intersect(currentUser.require().groupIds(), requested);
    }

    public void requireSite(UUID siteId) {
        Scope.require(currentUser.require().siteIds(), siteId);
    }

    public void requireGroup(UUID groupId) {
        Scope.require(currentUser.require().groupIds(), groupId);
    }
}
