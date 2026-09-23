import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Home from "../app/page";
import { json, renderApp, stubApi, TOKEN_INVALID } from "../test-utils";
import { AdminChrome } from "./AdminChrome";
import { RequireAuth } from "./RequireAuth";

const router = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/" }));

/** Sign-in state, sign-out and the sidebar all live in the app shell now (ticket 63), so `Home` is rendered
 *  through it here just like in the real app. */
function renderHome(languages?: string[]) {
  return renderApp(
    <AdminChrome>
      <Home />
    </AdminChrome>,
    languages,
  );
}

const HEALTH = { "GET /health/dependencies": () => json(200, { status: "up", dependencies: {} }) };
const TOKENS = { access_token: "secret-access", token_type: "Bearer", expires_in: 900 };
const ME = {
  id: "1",
  username: "rahim",
  display_name: "Rahim Uddin",
  preferred_language: null as string | null,
  roles: ["org_admin"],
  sites: [],
  groups: [],
};

beforeEach(() => router.replace.mockReset());
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("silent sign-in and the guarded dashboard", () => {
  it("sends a visitor with no session to the login screen", async () => {
    stubApi({ "POST /auth/refresh": () => json(401, TOKEN_INVALID) });
    renderApp(
      <RequireAuth>
        <p>secret content</p>
      </RequireAuth>,
    );

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/login/"));
    expect(screen.queryByText("secret content")).not.toBeInTheDocument();
  });

  it("resumes a session from the refresh cookie and shows the content, with the bearer token on API calls", async () => {
    const calls = stubApi({
      "POST /auth/refresh": () => json(200, TOKENS),
      "GET /auth/me": () => json(200, ME),
      ...HEALTH,
    });
    renderHome();

    expect(await screen.findByText("Signed in as Rahim Uddin")).toBeInTheDocument();
    expect(screen.getByText("Signed in with: Organisation Admin")).toBeInTheDocument();
    expect(router.replace).not.toHaveBeenCalled();
    const me = calls.find((c) => c.path === "/auth/me")!;
    expect((me.init.headers as Record<string, string>).Authorization).toBe("Bearer secret-access");
    const refresh = calls.find((c) => c.path === "/auth/refresh")!;
    expect((refresh.init.headers as Record<string, string>).Authorization).toBeUndefined();
    expect(refresh.init.credentials).toBe("same-origin");
  });

  it("links to site administration for an Org Admin only; the API enforces access either way (FR-CFG-001)", async () => {
    stubApi({ "POST /auth/refresh": () => json(200, TOKENS), "GET /auth/me": () => json(200, ME), ...HEALTH });
    const admin = renderHome();
    const quickLinks = within(await screen.findByRole("navigation", { name: "Your areas" }));
    const link = quickLinks.getByRole("link", { name: "Sites, zones and counters" });
    expect(link.getAttribute("href")).toMatch(/^\/sites\/?$/); // the export config adds the trailing slash
    admin.unmount();

    stubApi({ "POST /auth/refresh": () => json(200, TOKENS), "GET /auth/me": () => json(200, { ...ME, roles: ["agent"] }), ...HEALTH });
    renderHome();
    await screen.findByText("Signed in with: Agent");
    expect(screen.queryByRole("link", { name: "Sites, zones and counters" })).not.toBeInTheDocument();
  });

  it("uses the signed-in user's preferred language over the device setting", async () => {
    stubApi({
      "POST /auth/refresh": () => json(200, TOKENS),
      "GET /auth/me": () => json(200, { ...ME, preferred_language: "bn" }),
      ...HEALTH,
    });
    renderHome(["en-US"]);

    expect(await screen.findByText("Rahim Uddin হিসেবে সাইন ইন করা আছে")).toBeInTheDocument();
    expect(screen.getByText("সাইন ইন করা হয়েছে: প্রতিষ্ঠান অ্যাডমিন")).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("bn");
  });

  it("signs out on the server, forgets the token and shows the signed-out screen", async () => {
    const calls = stubApi({
      "POST /auth/refresh": () => json(200, TOKENS),
      "GET /auth/me": () => json(200, ME),
      "POST /auth/logout": () => new Response(null, { status: 204 }),
      ...HEALTH,
    });
    renderHome();
    await screen.findByText("Signed in as Rahim Uddin");

    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/signed-out/"));
    expect(calls.some((c) => c.method === "POST" && c.path === "/auth/logout")).toBe(true);
  });
});
