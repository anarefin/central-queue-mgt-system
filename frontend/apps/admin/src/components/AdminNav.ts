export interface AdminNavItem {
  href: string;
  /** The sidebar section this item is listed under (ticket 63). */
  group: string;
  /** An `admin.home.*` i18n key, shared with the overview page's own quick-link cards. */
  labelKey: string;
  /** Roles this item is offered to; the API is what actually enforces access (FR-CFG-103). */
  roles: readonly string[];
}

const SETUP_ADMINS: readonly string[] = ["system_admin", "org_admin"];
const OPS_ADMIN: readonly string[] = ["system_admin"];
const SCHEDULE_ADMINS: readonly string[] = ["system_admin", "org_admin", "team_admin"];

/**
 * The admin sidebar's full route list, grouped and role-gated exactly like the links `apps/admin/src/app/page.tsx`
 * offered before ticket 63 (same roles, same routes): system_admin/org_admin get Setup through Webhooks,
 * system_admin alone gets Ops, system/org/team admin share Availability/Notice board/Reports, team_admin alone gets
 * Feedback, and reception_operator alone gets Reception. Grouping is this ticket's own addition.
 */
export const ADMIN_NAV_ITEMS: AdminNavItem[] = [
  { href: "/setup/", group: "nav.group.setup", labelKey: "admin.home.setup", roles: SETUP_ADMINS },
  { href: "/sites/", group: "nav.group.organisation", labelKey: "admin.home.sites", roles: SETUP_ADMINS },
  { href: "/branding/", group: "nav.group.organisation", labelKey: "admin.home.branding", roles: SETUP_ADMINS },
  { href: "/catalogue/", group: "nav.group.queue", labelKey: "admin.home.catalogue", roles: SETUP_ADMINS },
  { href: "/numbering/", group: "nav.group.queue", labelKey: "admin.home.numbering", roles: SETUP_ADMINS },
  { href: "/priority/", group: "nav.group.queue", labelKey: "admin.home.priority", roles: SETUP_ADMINS },
  { href: "/breaks/", group: "nav.group.queue", labelKey: "admin.home.breaks", roles: SETUP_ADMINS },
  { href: "/availability/", group: "nav.group.queue", labelKey: "admin.home.availability", roles: SCHEDULE_ADMINS },
  { href: "/reception/", group: "nav.group.queue", labelKey: "admin.home.reception", roles: ["reception_operator"] },
  { href: "/devices/", group: "nav.group.devices", labelKey: "admin.home.devices", roles: SETUP_ADMINS },
  { href: "/visitor-import/", group: "nav.group.visitors", labelKey: "admin.home.visitorImport", roles: SETUP_ADMINS },
  { href: "/privacy/", group: "nav.group.visitors", labelKey: "admin.home.privacy", roles: SETUP_ADMINS },
  { href: "/feedback/", group: "nav.group.visitors", labelKey: "admin.home.feedback", roles: ["team_admin"] },
  { href: "/notifications/", group: "nav.group.communication", labelKey: "admin.home.notifications", roles: SETUP_ADMINS },
  { href: "/notice-board/", group: "nav.group.communication", labelKey: "admin.home.noticeBoard", roles: SCHEDULE_ADMINS },
  { href: "/webhooks/", group: "nav.group.communication", labelKey: "admin.home.webhooks", roles: SETUP_ADMINS },
  { href: "/reports/", group: "nav.group.insights", labelKey: "admin.home.reports", roles: SCHEDULE_ADMINS },
  { href: "/ops/", group: "nav.group.system", labelKey: "admin.home.ops", roles: OPS_ADMIN },
];

/** The items a user with these roles may see, in the fixed group order above (not the API: a convenience only). */
export function visibleAdminNavItems(roles: readonly string[]): AdminNavItem[] {
  return ADMIN_NAV_ITEMS.filter((item) => item.roles.some((role) => roles.includes(role)));
}
