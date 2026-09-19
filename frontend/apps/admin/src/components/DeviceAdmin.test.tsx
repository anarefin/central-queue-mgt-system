import type { DeviceView, Site } from "@qms/api-client";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { DeviceAdmin } from "./DeviceAdmin";

const SITE: Site = {
  id: "s1",
  name: "Main campus",
  code: "MAIN",
  timezone: "Asia/Dhaka",
  address: "1 Campus Road, Dhaka",
  default_language: "bn",
  enabled_languages: ["bn", "en"],
  active: true,
  created_at: "2026-09-19T20:30:00Z",
  updated_at: "2026-09-19T20:30:00Z",
};

const KIOSK: DeviceView = {
  id: "d1",
  kind: "kiosk",
  site_id: "s1",
  zone_id: null,
  label: "Front desk kiosk",
  active: true,
  paired_at: "2026-09-19T20:30:00Z",
  last_heartbeat_at: null,
  last_app_version: null,
  connectivity: "offline",
};

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function bodyOf(call: Recorded | undefined): Record<string, unknown> {
  return JSON.parse(String(call?.init.body)) as Record<string, unknown>;
}

function fakeApi(state: { sites: Site[]; devices: DeviceView[] }, extra: Routes = {}) {
  const routes: Routes = {
    ...NO_SESSION,
    "GET /sites": () => json(200, { items: state.sites }),
    "GET /devices": () => json(200, { items: state.devices }),
    "POST /devices/pairing-codes": () => json(201, { code: "ABCD1234", expires_at: "2026-09-19T20:40:00Z" }),
    "POST /devices/d1/revoke": () => {
      state.devices[0] = { ...state.devices[0]!, active: false };
      return json(200, state.devices[0]);
    },
    "POST /devices/d1/commands": () => new Response(null, { status: 204 }),
    ...extra,
  };
  return stubApi(routes);
}

describe("device administration screen", () => {
  it("shows an empty fleet and generates a pairing code for a kiosk", async () => {
    const state = { sites: [SITE], devices: [] as DeviceView[] };
    const calls = fakeApi(state);
    renderApp(<DeviceAdmin />);
    expect(await screen.findByText("No devices are paired yet.")).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText("Site"), "s1");
    await userEvent.type(screen.getByLabelText("Label"), "Front desk kiosk");
    await userEvent.click(screen.getByRole("button", { name: "Generate code" }));

    expect(await screen.findByText("Code: ABCD1234")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/devices/pairing-codes"))).toEqual({
      kind: "kiosk",
      site_id: "s1",
      label: "Front desk kiosk",
    });
  });

  it("shows the zone selector only for a display, and sends its zone_id", async () => {
    const state = { sites: [SITE], devices: [] as DeviceView[] };
    const calls = fakeApi(state, { "GET /sites/s1/zones": () => json(200, { items: [{ id: "z1", site_id: "s1", name: "Lobby", building_label: null, floor_label: "Ground", display_order: 0, active: true, created_at: SITE.created_at, updated_at: SITE.created_at }] }) });
    renderApp(<DeviceAdmin />);
    await screen.findByText("No devices are paired yet.");

    expect(screen.queryByLabelText("Zone")).not.toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Kind"), "display");
    await userEvent.selectOptions(screen.getByLabelText("Site"), "s1");
    await screen.findByLabelText("Zone");
    await userEvent.selectOptions(screen.getByLabelText("Zone"), "z1");
    await userEvent.type(screen.getByLabelText("Label"), "Lobby screen");
    await userEvent.click(screen.getByRole("button", { name: "Generate code" }));

    expect(await screen.findByText("Code: ABCD1234")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/devices/pairing-codes"))).toEqual({
      kind: "display",
      site_id: "s1",
      zone_id: "z1",
      label: "Lobby screen",
    });
  });

  it("shows a paired device's connectivity, and revoking it (after confirming) disables its actions", async () => {
    const state = { sites: [SITE], devices: [KIOSK] };
    const calls = fakeApi(state);
    renderApp(<DeviceAdmin />);

    expect(await screen.findByText("Front desk kiosk (Kiosk, Main campus)")).toBeInTheDocument();
    expect(screen.getByText("Offline")).toBeInTheDocument();
    expect(screen.getByText("Never reported in")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Revoke" }));
    expect(await screen.findByText("Revoke Front desk kiosk? It will be signed out at once and will need pairing again.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Confirm deactivation" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Revoke" })).toBeDisabled());
    expect(calls.some((c) => c.method === "POST" && c.path === "/devices/d1/revoke")).toBe(true);
  });

  it("pushes a reload command to a device", async () => {
    const state = { sites: [SITE], devices: [KIOSK] };
    const calls = fakeApi(state);
    renderApp(<DeviceAdmin />);
    await screen.findByText("Front desk kiosk (Kiosk, Main campus)");

    await userEvent.click(screen.getByRole("button", { name: "Push reload" }));

    await waitFor(() => expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/devices/d1/commands"))).toEqual({ command: "reload" }));
  });
});
