import { describe, expect, it } from "vitest";
import { canViewDashboard } from "./permissions";

describe("canViewDashboard (SRS §5.2, dashboard:view_all / dashboard:view_own_groups)", () => {
  it("grants system_admin, org_admin, team_admin and reception_operator", () => {
    expect(canViewDashboard(["system_admin"])).toBe(true);
    expect(canViewDashboard(["org_admin"])).toBe(true);
    expect(canViewDashboard(["team_admin"])).toBe(true);
    expect(canViewDashboard(["reception_operator"])).toBe(true);
  });

  it("grants agent, scoped to their own groups", () => {
    expect(canViewDashboard(["agent"])).toBe(true);
  });

  it("refuses a principal with neither permission", () => {
    expect(canViewDashboard([])).toBe(false);
    expect(canViewDashboard(["visitor"])).toBe(false);
    expect(canViewDashboard(["kiosk"])).toBe(false);
  });

  it("grants when any of several roles qualifies", () => {
    expect(canViewDashboard(["visitor", "agent"])).toBe(true);
  });
});
