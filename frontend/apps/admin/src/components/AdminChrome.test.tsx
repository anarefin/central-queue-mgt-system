import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi } from "../test-utils";
import { AdminChrome } from "./AdminChrome";
import { ADMIN_NAV_ITEMS, visibleAdminNavItems } from "./AdminNav";

const pathname = vi.hoisted(() => ({ value: "/" }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn() }),
  usePathname: () => pathname.value,
}));

const HEALTH = { "GET /health/dependencies": () => json(200, { status: "up", dependencies: {} }) };

function me(roles: string[]) {
  return { id: "u1", username: "asha", display_name: "Asha Rahman", preferred_language: null, roles, sites: ["s1"], groups: [] };
}

function renderChrome(roles: string[]) {
  stubApi({
    "POST /auth/refresh": () => json(200, { access_token: "tok", token_type: "Bearer", expires_in: 900 }),
    "GET /auth/me": () => json(200, me(roles)),
    ...HEALTH,
  });
  return renderApp(
    <AdminChrome>
      <p>Page content</p>
    </AdminChrome>,
  );
}

describe("visibleAdminNavItems (ticket 63's sidebar role gating)", () => {
  it("offers a system_admin every route except the ones reserved for team_admin or reception_operator", () => {
    const hrefs = visibleAdminNavItems(["system_admin"]).map((item) => item.href);
    expect(hrefs).toEqual(
      ADMIN_NAV_ITEMS.map((item) => item.href).filter((href) => href !== "/reception/" && href !== "/feedback/"),
    );
    expect(hrefs).toContain("/ops/");
  });

  it("offers an org_admin every route except Ops (system_admin only)", () => {
    const hrefs = visibleAdminNavItems(["org_admin"]).map((item) => item.href);
    expect(hrefs).toContain("/sites/");
    expect(hrefs).toContain("/catalogue/");
    expect(hrefs).not.toContain("/ops/");
    expect(hrefs).not.toContain("/feedback/");
    expect(hrefs).not.toContain("/reception/");
  });

  it("offers a team_admin only availability, notice board, reports and feedback", () => {
    const hrefs = visibleAdminNavItems(["team_admin"]).map((item) => item.href);
    expect(hrefs.sort()).toEqual(["/availability/", "/feedback/", "/notice-board/", "/reports/"].sort());
  });

  it("offers a reception_operator only the reception desk", () => {
    expect(visibleAdminNavItems(["reception_operator"]).map((item) => item.href)).toEqual(["/reception/"]);
  });

  it("offers an agent nothing (the console app is theirs, not admin)", () => {
    expect(visibleAdminNavItems(["agent"])).toEqual([]);
  });
});

describe("AdminChrome's sidebar (ticket 63)", () => {
  it("marks the current route with aria-current and leaves the others unmarked", async () => {
    pathname.value = "/sites/";
    renderChrome(["system_admin"]);

    const sites = await screen.findByRole("link", { name: "Sites, zones and counters" });
    expect(sites).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Service catalogue" })).not.toHaveAttribute("aria-current");
  });

  it("moves aria-current to the newly active route", async () => {
    pathname.value = "/catalogue/";
    renderChrome(["system_admin"]);

    expect(await screen.findByRole("link", { name: "Service catalogue" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Sites, zones and counters" })).not.toHaveAttribute("aria-current");
  });

  it("renders only a reception_operator's own item in the sidebar", async () => {
    pathname.value = "/reception/";
    renderChrome(["reception_operator"]);

    expect(await screen.findByRole("link", { name: "Reception desk" })).toHaveAttribute("aria-current", "page");
    expect(screen.queryByRole("link", { name: "Service catalogue" })).not.toBeInTheDocument();
  });

  it("renders no sidebar chrome at all for /login/", async () => {
    pathname.value = "/login/";
    stubApi({ "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) });
    renderApp(
      <AdminChrome>
        <p>Login form</p>
      </AdminChrome>,
    );

    expect(await screen.findByText("Login form")).toBeInTheDocument();
    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
  });

  it("names the signed-in user's site in the top bar, never its raw id", async () => {
    pathname.value = "/";
    stubApi({
      "POST /auth/refresh": () => json(200, { access_token: "tok", token_type: "Bearer", expires_in: 900 }),
      "GET /auth/me": () => json(200, me(["org_admin"])),
      "GET /sites": () => json(200, { items: [{ id: "s1", code: "AARONG-CS", name: "Aarong Central Services" }] }),
      ...HEALTH,
    });
    renderApp(
      <AdminChrome>
        <p>Page content</p>
      </AdminChrome>,
    );

    expect(await screen.findByText("Aarong Central Services")).toBeInTheDocument();
    expect(screen.queryByText("s1")).not.toBeInTheDocument();
  });

  it("shows no site at all, rather than its id, to a user who may not read the site list", async () => {
    pathname.value = "/";
    const calls = stubApi({
      "POST /auth/refresh": () => json(200, { access_token: "tok", token_type: "Bearer", expires_in: 900 }),
      "GET /auth/me": () => json(200, me(["reception_operator"])),
      ...HEALTH,
    });
    renderApp(
      <AdminChrome>
        <p>Page content</p>
      </AdminChrome>,
    );

    expect(await screen.findByText("Page content")).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.queryByText("s1")).not.toBeInTheDocument();
    expect(calls.some((call) => call.path === "/sites"), "not even asked: the API would refuse").toBe(false);
  });
});
