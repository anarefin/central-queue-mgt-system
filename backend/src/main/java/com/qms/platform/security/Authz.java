package com.qms.platform.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Programmatic permission checks for cases {@code @PreAuthorize} cannot express, such as a permission that depends on
 * a field of the request. Enforcement is still server-side and still at the service layer (FR-CFG-103, API-016).
 */
@Component
public class Authz {

    public boolean has(Permission permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(permission.authority()));
    }

    public void require(Permission permission) {
        if (!has(permission)) {
            throw new AccessDeniedException("Access Denied");
        }
    }
}
