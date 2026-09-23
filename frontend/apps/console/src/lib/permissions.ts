/**
 * Which staff roles carry `dashboard:view_all` or `dashboard:view_own_groups` (SRS §5.2, `PermissionMatrix.java`):
 * System Admin and Org Admin have `view_all`; Team Admin and Reception Operator have `view_own_groups`; Agent has
 * it too, scoped to their own groups (`Access.OWN`). `/auth/me` carries only role names, never the permission
 * list itself (API-011: "no permission list travels in the token"), so this mirrors the matrix's roles here, the
 * same shape `AdminNav.ts`'s own role lists already use for admin's sidebar — a convenience gate only, since the
 * API is what actually enforces access on `GET /dashboard/live` (FR-CFG-103, ticket 64).
 */
const DASHBOARD_ROLES: readonly string[] = ["system_admin", "org_admin", "team_admin", "reception_operator", "agent"];

export function canViewDashboard(roles: readonly string[]): boolean {
  return roles.some((role) => DASHBOARD_ROLES.includes(role));
}
