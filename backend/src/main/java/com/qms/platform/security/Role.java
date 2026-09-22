package com.qms.platform.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * The fixed, code-defined staff roles of SRS §5.2 (FR-CFG-101). Which users hold which roles is configuration; what a
 * role may do is not. Only the display name is localisable, and it lives in the language packs as {@code roles.<wire>}.
 * Device, visitor and reporting principals get their own roles with the tickets that introduce them.
 *
 * <p>{@link #KIOSK} and {@link #DISPLAY} (ticket 24, SRS §5.1/§20.2) are device roles, {@link #VISITOR} (ticket
 * 41) is a registered visitor's own role, and {@link #HOST_SYSTEM} (ticket 58, SRS §20.2, §22.4, FR-INT-030) is a
 * client's own service account; none of the four are staff roles: they carry no entries in {@code PermissionMatrix}
 * (that matrix is SRS §5.2, staff only) and are authorised directly by {@code hasRole(...)} on the endpoints that
 * need them.
 */
public enum Role {
    SYSTEM_ADMIN("system_admin", true),
    ORG_ADMIN("org_admin", true),
    TEAM_ADMIN("team_admin", true),
    AGENT("agent", false),
    RECEPTION_OPERATOR("reception_operator", false),
    KIOSK("kiosk", false),
    DISPLAY("display", false),
    /** A registered visitor, signed in by email + OTP (ticket 41, FR-MOB-001, §5.1). Carries no staff permission: it
     * is authorised directly by {@code hasRole('VISITOR')} on the few endpoints a visitor may call, plus an
     * object-level "own record" check in the service layer, the same shape {@link Access#OWN} already is for staff. */
    VISITOR("visitor", false),
    /** A client's own system (a bank's app, a hospital portal, a BI tool), authenticated as a service account with a
     * client id and secret (ticket 58, SRS §20.2, §22.4, FR-INT-030). Scoped to the sites its service account names,
     * the same {@code sites} claim shape a device or a scoped staff role already carries; authorised directly by
     * {@code hasRole('HOST_SYSTEM')} on the handful of endpoints §22.4 opens to it, alongside the permission every
     * other principal needs there — no privileged internal path. */
    HOST_SYSTEM("host_system", false);

    private final String wire;
    private final boolean admin;

    Role(String wire, boolean admin) {
        this.wire = wire;
        this.admin = admin;
    }

    /** The value carried in the token's {@code roles} claim and stored in {@code role_assignments.role}. */
    public String wire() {
        return wire;
    }

    /** Admin roles get the short idle timeout (NFR-SEC-004). */
    public boolean isAdmin() {
        return admin;
    }

    public static Role fromWire(String wire) {
        return tryFromWire(wire).orElseThrow(() -> new IllegalArgumentException("Unknown role: " + wire));
    }

    public static Optional<Role> tryFromWire(String wire) {
        return Arrays.stream(values()).filter(role -> role.wire.equals(wire)).findFirst();
    }
}
