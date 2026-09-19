package com.qms.platform.security;

import java.util.Arrays;

/** One row of the SRS §5.2 permission matrix. */
public enum Permission {
    CONFIG_ORG_SITES_ZONES("config:org_sites_zones"),
    CONFIG_SERVICE_CATALOGUE("config:service_catalogue"),
    CONFIG_PRIORITY_ROUTING("config:priority_routing"),
    USER_MANAGE("user:manage"),
    ROLE_ASSIGN("role:assign"),
    TEAM_MEMBER_REQUEST("team_member:request"),
    TEAM_MEMBER_APPROVE("team_member:approve"),
    COUNTER_ALLOCATION_REQUEST("counter_allocation:request"),
    COUNTER_ALLOCATION_APPROVE("counter_allocation:approve"),
    COUNTER_SESSION_OPEN_CLOSE("counter_session:open_close"),
    TICKET_CALL_SERVE_COMPLETE("ticket:call_serve_complete"),
    TICKET_TRANSFER("ticket:transfer"),
    TICKET_REPRIORITISE("ticket:reprioritise"),
    TICKET_ISSUE("ticket:issue"),
    TICKET_CANCEL("ticket:cancel"),
    AGENT_AVAILABILITY_FORCE_SET("agent_availability:force_set"),
    DASHBOARD_VIEW_ALL("dashboard:view_all"),
    DASHBOARD_VIEW_OWN_GROUPS("dashboard:view_own_groups"),
    REPORTS_RUN_EXPORT("reports:run_export"),
    VISITOR_PII_VIEW("visitor_pii:view"),
    NOTICE_BOARD_MANAGE("notice_board:manage"),
    AUDIT_READ("audit:read"),
    APPOINTMENT_BOOK("appointment:book");

    private final String wire;

    Permission(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** Granted authority for an unrestricted {@link Access#ALLOWED}, usable in {@code @PreAuthorize}. */
    public String authority() {
        return "perm:" + wire;
    }

    /** Granted authority for {@link Access#OWN}; the service must still check the record is the caller's. */
    public String ownAuthority() {
        return authority() + ":own";
    }

    public static Permission fromWire(String wire) {
        return Arrays.stream(values())
                .filter(p -> p.wire.equals(wire))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown permission: " + wire));
    }
}
