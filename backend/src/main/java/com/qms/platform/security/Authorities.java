package com.qms.platform.security;

/**
 * Compile-time constants for {@code @PreAuthorize("hasAuthority(T(...Authorities).X)")}; annotations cannot call
 * {@link Permission#authority()}. Each constant is named like its {@link Permission} and PermissionMatrixTest checks
 * the values agree.
 */
public final class Authorities {

    public static final String CONFIG_ORG_SITES_ZONES = "perm:config:org_sites_zones";
    public static final String CONFIG_SERVICE_CATALOGUE = "perm:config:service_catalogue";
    public static final String CONFIG_PRIORITY_ROUTING = "perm:config:priority_routing";
    public static final String USER_MANAGE = "perm:user:manage";
    public static final String ROLE_ASSIGN = "perm:role:assign";
    public static final String TEAM_MEMBER_REQUEST = "perm:team_member:request";
    public static final String TEAM_MEMBER_APPROVE = "perm:team_member:approve";
    public static final String COUNTER_ALLOCATION_REQUEST = "perm:counter_allocation:request";
    public static final String COUNTER_ALLOCATION_APPROVE = "perm:counter_allocation:approve";
    public static final String COUNTER_SESSION_OPEN_CLOSE = "perm:counter_session:open_close";
    public static final String TICKET_CALL_SERVE_COMPLETE = "perm:ticket:call_serve_complete";
    public static final String TICKET_TRANSFER = "perm:ticket:transfer";
    public static final String TICKET_REPRIORITISE = "perm:ticket:reprioritise";
    public static final String TICKET_ISSUE = "perm:ticket:issue";
    public static final String TICKET_CANCEL = "perm:ticket:cancel";
    public static final String TICKET_CHECKIN = "perm:ticket:checkin";
    public static final String AGENT_AVAILABILITY_FORCE_SET = "perm:agent_availability:force_set";
    public static final String DASHBOARD_VIEW_ALL = "perm:dashboard:view_all";
    public static final String DASHBOARD_VIEW_OWN_GROUPS = "perm:dashboard:view_own_groups";
    public static final String REPORTS_RUN_EXPORT = "perm:reports:run_export";
    public static final String VISITOR_PII_VIEW = "perm:visitor_pii:view";
    public static final String NOTICE_BOARD_MANAGE = "perm:notice_board:manage";
    public static final String AUDIT_READ = "perm:audit:read";
    public static final String APPOINTMENT_BOOK = "perm:appointment:book";
    public static final String APPOINTMENT_CHECKIN = "perm:appointment:checkin";
    public static final String OPS_DIAGNOSTICS_EXPORT = "perm:ops:diagnostics_export";

    private Authorities() {}
}
