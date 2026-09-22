package com.qms.platform.security;

import static com.qms.platform.security.Permission.*;
import static com.qms.platform.security.Role.*;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * SRS §5.2, defined in code (FR-CFG-101). Effective permissions are the union of the sets of all the user's roles.
 * Authorities are derived from the roles claim on the server; no permission list travels in the token (API-011).
 */
public final class PermissionMatrix {

    private static final Map<Permission, Map<Role, Access>> MATRIX = new EnumMap<>(Permission.class);

    static {
        allow(CONFIG_ORG_SITES_ZONES, SYSTEM_ADMIN, ORG_ADMIN);
        allow(CONFIG_SERVICE_CATALOGUE, SYSTEM_ADMIN, ORG_ADMIN);
        allow(CONFIG_PRIORITY_ROUTING, SYSTEM_ADMIN, ORG_ADMIN);
        allow(USER_MANAGE, SYSTEM_ADMIN, ORG_ADMIN);
        allow(ROLE_ASSIGN, SYSTEM_ADMIN, ORG_ADMIN);
        allow(TEAM_MEMBER_REQUEST, SYSTEM_ADMIN, ORG_ADMIN, TEAM_ADMIN);
        allow(TEAM_MEMBER_APPROVE, SYSTEM_ADMIN, ORG_ADMIN);
        allow(COUNTER_ALLOCATION_REQUEST, SYSTEM_ADMIN, ORG_ADMIN, TEAM_ADMIN);
        allow(COUNTER_ALLOCATION_APPROVE, SYSTEM_ADMIN, ORG_ADMIN);
        allow(COUNTER_SESSION_OPEN_CLOSE, SYSTEM_ADMIN, ORG_ADMIN, TEAM_ADMIN);
        own(COUNTER_SESSION_OPEN_CLOSE, AGENT);
        allow(TICKET_CALL_SERVE_COMPLETE, TEAM_ADMIN);
        own(TICKET_CALL_SERVE_COMPLETE, AGENT);
        allow(TICKET_TRANSFER, ORG_ADMIN, TEAM_ADMIN);
        own(TICKET_TRANSFER, AGENT);
        allow(TICKET_REPRIORITISE, SYSTEM_ADMIN, ORG_ADMIN, TEAM_ADMIN, RECEPTION_OPERATOR);
        allow(TICKET_ISSUE, RECEPTION_OPERATOR);
        allow(TICKET_CANCEL, SYSTEM_ADMIN, ORG_ADMIN, TEAM_ADMIN, RECEPTION_OPERATOR);
        own(TICKET_CANCEL, AGENT);
        allow(TICKET_CHECKIN, SYSTEM_ADMIN, ORG_ADMIN, TEAM_ADMIN, RECEPTION_OPERATOR);
        allow(AGENT_AVAILABILITY_FORCE_SET, SYSTEM_ADMIN, ORG_ADMIN, TEAM_ADMIN);
        allow(DASHBOARD_VIEW_ALL, SYSTEM_ADMIN, ORG_ADMIN);
        allow(DASHBOARD_VIEW_OWN_GROUPS, SYSTEM_ADMIN, ORG_ADMIN, TEAM_ADMIN, RECEPTION_OPERATOR);
        own(DASHBOARD_VIEW_OWN_GROUPS, AGENT);
        allow(REPORTS_RUN_EXPORT, SYSTEM_ADMIN, ORG_ADMIN, TEAM_ADMIN);
        allow(VISITOR_PII_VIEW, SYSTEM_ADMIN, ORG_ADMIN, TEAM_ADMIN, RECEPTION_OPERATOR);
        own(VISITOR_PII_VIEW, AGENT);
        allow(NOTICE_BOARD_MANAGE, SYSTEM_ADMIN, ORG_ADMIN, TEAM_ADMIN);
        allow(AUDIT_READ, SYSTEM_ADMIN, ORG_ADMIN);
        allow(APPOINTMENT_BOOK, SYSTEM_ADMIN, ORG_ADMIN, TEAM_ADMIN, RECEPTION_OPERATOR);
        allow(APPOINTMENT_CHECKIN, SYSTEM_ADMIN, ORG_ADMIN, TEAM_ADMIN, RECEPTION_OPERATOR);
        // FR-OPS-040: the diagnostics bundle carries a redacted configuration snapshot alongside recent audit
        // events, so it is restricted to System Administrator, narrower than plain audit:read (Org Admin too).
        allow(OPS_DIAGNOSTICS_EXPORT, SYSTEM_ADMIN);
    }

    private PermissionMatrix() {}

    private static void allow(Permission permission, Role... roles) {
        for (Role role : roles) {
            MATRIX.computeIfAbsent(permission, p -> new EnumMap<>(Role.class)).put(role, Access.ALLOWED);
        }
    }

    private static void own(Permission permission, Role... roles) {
        for (Role role : roles) {
            MATRIX.computeIfAbsent(permission, p -> new EnumMap<>(Role.class)).put(role, Access.OWN);
        }
    }

    public static Access access(Role role, Permission permission) {
        return MATRIX.getOrDefault(permission, Map.of()).getOrDefault(role, Access.DENIED);
    }

    /** Granted authorities for a set of roles: the union of their permissions, {@code ALLOWED} beating {@code OWN}. */
    public static Set<String> authoritiesFor(Set<Role> roles) {
        Set<String> authorities = new HashSet<>();
        for (Permission permission : Permission.values()) {
            boolean allowed = roles.stream().anyMatch(r -> access(r, permission) == Access.ALLOWED);
            boolean own = roles.stream().anyMatch(r -> access(r, permission) == Access.OWN);
            if (allowed) {
                authorities.add(permission.authority());
            } else if (own) {
                authorities.add(permission.ownAuthority());
            }
        }
        return authorities;
    }
}
