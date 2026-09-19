import type { Counter, Site, Zone } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { SiteAdmin } from "./SiteAdmin";

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
const ZONE: Zone = {
  id: "z1",
  site_id: "s1",
  name: "Ground waiting",
  building_label: "Block B",
  floor_label: "Ground",
  display_order: 0,
  active: true,
  created_at: SITE.created_at,
  updated_at: SITE.updated_at,
};
const COUNTER: Counter = {
  id: "c1",
  zone_id: "z1",
  site_id: "s1",
  label: "Counter 3",
  location_note: "Behind the pillar",
  active: true,
  created_at: SITE.created_at,
  updated_at: SITE.updated_at,
};

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function bodyOf(call: Recorded | undefined): Record<string, unknown> {
  return JSON.parse(String(call?.init.body)) as Record<string, unknown>;
}

/** An in-memory API for the three levels, so a write is visible on the next list the screen fetches. */
function fakeApi(state: { sites: Site[]; zones: Zone[]; counters: Counter[] }, extra: Routes = {}) {
  const routes: Routes = {
    ...NO_SESSION,
    "GET /sites": () => json(200, { items: state.sites }),
    "GET /sites/s1/zones": () => json(200, { items: state.zones }),
    "GET /zones/z1/counters": () => json(200, { items: state.counters }),
    "POST /sites": (init) => {
      const input = JSON.parse(String(init.body)) as Partial<Site>;
      const created = { ...SITE, id: `s${state.sites.length + 1}`, ...input } as Site;
      state.sites.push(created);
      return json(201, created);
    },
    "PATCH /sites/s1": (init) => {
      Object.assign(state.sites[0]!, JSON.parse(String(init.body)));
      return json(200, state.sites[0]);
    },
    "POST /sites/s1/deactivate": () => {
      state.sites[0] = { ...state.sites[0]!, active: false };
      state.zones = state.zones.map((z) => ({ ...z, active: false }));
      return json(200, state.sites[0]);
    },
    "POST /sites/s1/activate": () => {
      state.sites[0] = { ...state.sites[0]!, active: true };
      return json(200, state.sites[0]);
    },
    "POST /sites/s1/zones": (init) => {
      const created = { ...ZONE, id: `z${state.zones.length + 1}`, ...(JSON.parse(String(init.body)) as object) } as Zone;
      state.zones.push(created);
      return json(201, created);
    },
    "POST /zones/z1/counters": (init) => {
      const created = { ...COUNTER, id: `c${state.counters.length + 1}`, ...(JSON.parse(String(init.body)) as object) } as Counter;
      state.counters.push(created);
      return json(201, created);
    },
    ...extra,
  };
  return stubApi(routes);
}

describe("site administration screen", () => {
  it("renders each site's timestamps in that site's own timezone (FR-CFG-002)", async () => {
    // 20:30 UTC on 19 September is 02:30 the next morning in Dhaka (UTC+6) and 16:30 in New York (UTC-4).
    fakeApi({ sites: [SITE, { ...SITE, id: "s2", name: "Branch", code: "BR", timezone: "America/New_York", address: "5 Broadway, New York", default_language: "en", enabled_languages: ["en"] }], zones: [], counters: [] });
    renderApp(<SiteAdmin />);

    expect(await screen.findByText("Last changed Sep 20, 2026, 2:30 AM (Asia/Dhaka)")).toBeInTheDocument();
    expect(screen.getByText("Last changed Sep 19, 2026, 4:30 PM (America/New_York)")).toBeInTheDocument();
    expect(screen.getByText("1 Campus Road, Dhaka")).toBeInTheDocument();
    expect(screen.getByText("Default language: Bangla. Enabled: Bangla, English.")).toBeInTheDocument();
    expect(screen.getByText("Default language: English. Enabled: English.")).toBeInTheDocument();
  });

  it("adds a site with timezone, address, default language and ordered languages, with no reload or restart (FR-CFG-001, NFR-SCL-002)", async () => {
    const state = { sites: [] as Site[], zones: [], counters: [] };
    const calls = fakeApi(state);
    renderApp(<SiteAdmin />);
    expect(await screen.findByText("There are no sites yet. Add the first one below.")).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText("Name"), "North campus");
    await userEvent.type(screen.getByLabelText("Code"), "NORTH");
    await userEvent.type(screen.getByLabelText("Time zone"), "Asia/Dhaka");
    await userEvent.type(screen.getByLabelText("Address"), "2 North Road");
    await userEvent.selectOptions(screen.getByLabelText("Default language"), "bn");
    await userEvent.type(screen.getByLabelText("Enabled languages, in display order"), "bn, en");
    await userEvent.click(screen.getByRole("button", { name: "Add a site" }));

    expect(await screen.findByText("North campus (NORTH)")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/sites"))).toEqual({
      name: "North campus",
      code: "NORTH",
      timezone: "Asia/Dhaka",
      address: "2 North Road",
      default_language: "bn",
      enabled_languages: ["bn", "en"],
    });
    expect(screen.queryByText("There are no sites yet. Add the first one below.")).not.toBeInTheDocument();
  });

  it("names the fields to check, in words, when the server refuses the input", async () => {
    fakeApi(
      { sites: [], zones: [], counters: [] },
      {
        "POST /sites": () =>
          json(400, {
            error: {
              code: "validation_failed",
              message: "x",
              details: { fields: [{ field: "timezone", code: "unknown_timezone" }] },
              trace_id: "t",
            },
          }),
      },
    );
    renderApp(<SiteAdmin />);
    await screen.findByText("There are no sites yet. Add the first one below.");

    await userEvent.type(screen.getByLabelText("Time zone"), "Mars/Olympus");
    await userEvent.click(screen.getByRole("button", { name: "Add a site" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The request contains invalid data.");
    expect(alert).toHaveTextContent("Check these fields: Time zone");
    expect(alert.textContent).not.toMatch(/errors\.|validation_failed/);
  });

  it("renames a site", async () => {
    const calls = fakeApi({ sites: [{ ...SITE }], zones: [], counters: [] });
    renderApp(<SiteAdmin />);
    await screen.findByText("Main campus (MAIN)");

    await userEvent.click(screen.getByRole("button", { name: "Edit Main campus" }));
    const name = screen.getByDisplayValue("Main campus");
    await userEvent.clear(name);
    await userEvent.type(name, "North campus");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText("North campus (MAIN)")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "PATCH"))).toMatchObject({ name: "North campus", timezone: "Asia/Dhaka" });
  });

  it("asks before deactivating, deactivates softly and keeps the site listed so it can be activated again (FR-CFG-001)", async () => {
    const calls = fakeApi({ sites: [{ ...SITE }], zones: [], counters: [] });
    renderApp(<SiteAdmin />);
    await screen.findByText("Main campus (MAIN)");
    expect(screen.getByText("Active")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Deactivate Main campus" }));
    expect(calls.some((c) => c.path.endsWith("/deactivate"))).toBe(false);
    expect(screen.getByText(/also deactivates its zones and counters/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Confirm deactivation" }));

    expect(await screen.findByText("Inactive")).toBeInTheDocument();
    expect(screen.getByText("Main campus (MAIN)")).toBeInTheDocument();
    expect(calls.filter((c) => c.method === "DELETE")).toHaveLength(0);

    await userEvent.click(screen.getByRole("button", { name: "Activate Main campus" }));
    expect(await screen.findByText("Active")).toBeInTheDocument();
  });

  it("shows a site's zones with building and floor, and their counters with the location note (FR-CFG-003, FR-CFG-004)", async () => {
    fakeApi({ sites: [SITE], zones: [ZONE, { ...ZONE, id: "z2", name: "Annex", building_label: null, floor_label: "3rd" }], counters: [COUNTER] });
    renderApp(<SiteAdmin />);
    await screen.findByText("Main campus (MAIN)");

    await userEvent.click(screen.getByRole("button", { name: "Zones Main campus" }));
    expect(await screen.findByText("Zones of Main campus")).toBeInTheDocument();
    expect(screen.getByText("Building: Block B · Floor: Ground")).toBeInTheDocument();
    expect(screen.getByText("Floor: 3rd")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Counters Ground waiting" }));
    expect(await screen.findByText("Counters of Ground waiting")).toBeInTheDocument();
    expect(screen.getByText("Counter 3")).toBeInTheDocument();
    expect(screen.getByText("Location: Behind the pillar")).toBeInTheDocument();
  });

  it("adds a zone with floor and optional building, then a counter with a label and note", async () => {
    const state = { sites: [SITE], zones: [] as Zone[], counters: [] as Counter[] };
    const calls = fakeApi(state);
    renderApp(<SiteAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Zones Main campus" }));
    await screen.findByText("This site has no zones yet.");

    const zoneCard = screen.getByText("Zones of Main campus").closest("section")!;
    await userEvent.type(within(zoneCard).getByLabelText("Name"), "Ground waiting");
    await userEvent.type(within(zoneCard).getByLabelText("Floor label"), "Ground");
    await userEvent.type(within(zoneCard).getByLabelText("Building label (optional)"), "Block B");
    await userEvent.click(within(zoneCard).getByRole("button", { name: "Add a zone" }));

    expect(await screen.findByText("Building: Block B · Floor: Ground")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.path === "/sites/s1/zones" && c.method === "POST"))).toEqual({
      name: "Ground waiting",
      floor_label: "Ground",
      building_label: "Block B",
    });

    state.zones[0] = { ...state.zones[0]!, id: "z1" };
    await userEvent.click(screen.getByRole("button", { name: "Counters Ground waiting" }));
    await screen.findByText("This zone has no counters yet.");
    const counterCard = screen.getByText("Counters of Ground waiting").closest("section")!;
    await userEvent.type(within(counterCard).getByLabelText("Display label"), "Counter 3");
    await userEvent.type(within(counterCard).getByLabelText("Location note (optional)"), "Behind the pillar");
    await userEvent.click(within(counterCard).getByRole("button", { name: "Add a counter" }));

    expect(await screen.findByText("Location: Behind the pillar")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.path === "/zones/z1/counters" && c.method === "POST"))).toEqual({
      label: "Counter 3",
      location_note: "Behind the pillar",
    });
  });

  it("shows the API's refusal when a zone cannot be reactivated under an inactive site", async () => {
    fakeApi(
      { sites: [SITE], zones: [{ ...ZONE, active: false }], counters: [] },
      { "POST /zones/z1/activate": () => json(409, { error: { code: "conflict", message: "x", details: { reason: "parent_inactive" }, trace_id: "t" } }) },
    );
    renderApp(<SiteAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Zones Main campus" }));

    await userEvent.click(await screen.findByRole("button", { name: "Activate Ground waiting" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The request conflicts with the current state.");
  });

  it("renders every label in Bangla, with dates in the site timezone and Bengali digits", async () => {
    fakeApi({ sites: [SITE], zones: [], counters: [] });
    renderApp(<SiteAdmin />, ["bn-BD"]);

    await screen.findByText("Main campus (MAIN)");
    expect(screen.getByText("সাইট")).toBeInTheDocument();
    expect(screen.getByText("সক্রিয়")).toBeInTheDocument();
    expect(screen.getByText("ডিফল্ট ভাষা: বাংলা। সক্রিয়: বাংলা, ইংরেজি।")).toBeInTheDocument();
    expect(screen.getByText(/সর্বশেষ পরিবর্তন .*২০২৬.*\(Asia\/Dhaka\)/)).toBeInTheDocument();
    expect(screen.getByLabelText("সময় অঞ্চল")).toBeInTheDocument();
    await waitFor(() => expect(document.documentElement.lang).toBe("bn"));
  });
});
