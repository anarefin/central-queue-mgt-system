import { I18nProvider } from "@qms/i18n/react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RuntimeProvider } from "../lib/runtime";
import { DevicePairing } from "./DevicePairing";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

type Route = (init: RequestInit) => Response | Promise<Response>;
type Routes = Record<string, Route>;

interface Recorded {
  method: string;
  path: string;
  init: RequestInit;
}

/** A do-nothing WebSocket stand-in: jsdom has none, and the realtime client must not throw trying to make one. */
class FakeWebSocket {
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onclose: ((event: { code?: number }) => void) | null = null;
  onerror: (() => void) | null = null;
  send() {}
  close() {}
}

function stubApi(routes: Routes): Recorded[] {
  const calls: Recorded[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit = {}) => {
    if (url.endsWith("/config.json")) return json(200, { apiOrigin: "" });
    const path = url.replace(/^.*\/api\/v1/, "");
    const method = init.method ?? "GET";
    calls.push({ method, path, init });
    const route = routes[`${method} ${path}`];
    if (!route) throw new TypeError(`unrouted ${method} ${path}`);
    return route(init);
  });
  vi.stubGlobal("WebSocket", FakeWebSocket as unknown as typeof WebSocket);
  return calls;
}

function renderPairing() {
  return render(
    <I18nProvider loadExtra={false}>
      <RuntimeProvider>
        <DevicePairing />
      </RuntimeProvider>
    </I18nProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  document.documentElement.style.removeProperty("--color-primary");
});

const BOOTSTRAP = {
  branding: { site_name: "Main campus", default_language: "en" },
  languages: ["en"],
  layout: null,
  service_tree: [],
};

describe("display device pairing", () => {
  it("shows the pairing form when unpaired, then loads bootstrap and reports a heartbeat once paired", async () => {
    const calls = stubApi({
      "POST /devices/pair": () =>
        json(201, {
          device_id: "d1",
          kind: "kiosk",
          site_id: "s1",
          zone_id: null,
          access_token: "access-1",
          token_type: "Bearer",
          expires_in: 900,
          refresh_token: "refresh-1",
        }),
      "GET /config/bootstrap": () => json(200, BOOTSTRAP),
      "POST /devices/d1/heartbeat": () => new Response(null, { status: 204 }),
    });
    renderPairing();
    expect(await screen.findByText("Pair this device")).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText("Pairing code"), "abcd1234");
    await userEvent.click(screen.getByRole("button", { name: "Pair" }));

    expect(await screen.findByText("Main campus")).toBeInTheDocument();
    expect(calls.some((c) => c.method === "POST" && c.path === "/devices/pair")).toBe(true);
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/devices/d1/heartbeat")).toBe(true));
  });

  it("shows an error when the pairing code is refused", async () => {
    stubApi({
      "POST /devices/pair": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }),
    });
    renderPairing();
    await screen.findByText("Pair this device");

    await userEvent.type(screen.getByLabelText("Pairing code"), "bogus");
    await userEvent.click(screen.getByRole("button", { name: "Pair" }));

    expect(await screen.findByText("Your session token is invalid or has expired.")).toBeInTheDocument();
  });

  it("shows the organisation's logo and applies its primary colour once paired (ticket 27, FR-CFG-030)", async () => {
    stubApi({
      "POST /devices/pair": () =>
        json(201, {
          device_id: "d1",
          kind: "display",
          site_id: "s1",
          zone_id: "z1",
          access_token: "access-1",
          token_type: "Bearer",
          expires_in: 900,
          refresh_token: "refresh-1",
        }),
      "GET /config/bootstrap": () =>
        json(200, {
          ...BOOTSTRAP,
          branding: {
            site_name: "Main campus",
            default_language: "en",
            org_name: "Northside Clinic",
            primary_color: "#123abc",
            logo_url: "https://example.org/logo.png",
          },
        }),
      "POST /devices/d1/heartbeat": () => new Response(null, { status: 204 }),
    });
    renderPairing();
    await screen.findByText("Pair this device");

    await userEvent.type(screen.getByLabelText("Pairing code"), "abcd1234");
    await userEvent.click(screen.getByRole("button", { name: "Pair" }));

    const logo = await screen.findByAltText("Northside Clinic logo");
    expect(logo).toHaveAttribute("src", "https://example.org/logo.png");
    // applyBrand (ticket 62) sets the root token every `bg-primary`/`text-primary` utility reads, rather than a
    // one-off inline style scoped to this component's own wrapper.
    await waitFor(() => expect(document.documentElement.style.getPropertyValue("--color-primary")).toBe("#123abc"));
    // ticket 65: exactly one <main> landmark around the whole board, whatever layout is configured.
    expect(screen.getAllByRole("main")).toHaveLength(1);
  });
});
