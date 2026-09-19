package com.qms.identity;

import java.util.UUID;

/**
 * Staff authentication behind a provider interface (FR-INT-001). Phase 1 ships {@link LocalPasswordProvider}; OIDC and
 * LDAP/AD providers arrive in Phase 2 as further implementations, and roles they learn from external groups reach the
 * user through {@link RoleMapper} (FR-INT-002). Whatever the provider, the result is the same: a user id, after which
 * the application issues its own tokens.
 */
public interface IdentityProvider {

    /**
     * Verifies the credentials of a staff user.
     *
     * @throws com.qms.platform.ApiException {@code invalid_credentials} or {@code account_locked} when they do not check out
     */
    Authenticated authenticate(String username, String password);

    /** @param passwordExpired true when a local password has passed its configured age (NFR-SEC-001) */
    record Authenticated(UUID userId, boolean passwordExpired) {}
}
