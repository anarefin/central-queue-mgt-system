import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Home from "../app/page";
import { json, renderApp, stubApi, TOKEN_INVALID } from "../test-utils";
import { RequireAuth } from "./RequireAuth";

const router = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

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
    renderApp(<Home />);

    expect(await screen.findByText("Signed in as Rahim Uddin")).toBeInTheDocument();
    expect(screen.getByText("Organisation Admin")).toBeInTheDocument();
    expect(router.replace).not.toHaveBeenCalled();
    const me = calls.find((c) => c.path === "/auth/me")!;
    expect((me.init.headers as Record<string, string>).Authorization).toBe("Bearer secret-access");
    const refresh = calls.find((c) => c.path === "/auth/refresh")!;
    expect((refresh.init.headers as Record<string, string>).Authorization).toBeUndefined();
    expect(refresh.init.credentials).toBe("same-origin");
  });

  it("uses the signed-in user's preferred language over the device setting", async () => {
    stubApi({
      "POST /auth/refresh": () => json(200, TOKENS),
      "GET /auth/me": () => json(200, { ...ME, preferred_language: "bn" }),
      ...HEALTH,
    });
    renderApp(<Home />, ["en-US"]);

    expect(await screen.findByText("Rahim Uddin হিসেবে সাইন ইন করা আছে")).toBeInTheDocument();
    expect(screen.getByText("প্রতিষ্ঠান অ্যাডমিন")).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("bn");
  });

  it("signs out on the server, forgets the token and shows the signed-out screen", async () => {
    const calls = stubApi({
      "POST /auth/refresh": () => json(200, TOKENS),
      "GET /auth/me": () => json(200, ME),
      "POST /auth/logout": () => new Response(null, { status: 204 }),
      ...HEALTH,
    });
    renderApp(<Home />);
    await screen.findByText("Signed in as Rahim Uddin");

    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/signed-out/"));
    expect(calls.some((c) => c.method === "POST" && c.path === "/auth/logout")).toBe(true);
  });
});
