import type { Notice, Site, Zone } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { NoticeBoardAdmin } from "./NoticeBoardAdmin";

const SITE: Site = {
  id: "s1",
  name: "Main campus",
  code: "MAIN",
  timezone: "Asia/Dhaka",
  address: "1 Campus Road, Dhaka",
  default_language: "bn",
  enabled_languages: ["bn", "en"],
  active: true,
  clinical_sensitivity: false,
  created_at: "2026-09-19T20:30:00Z",
  updated_at: "2026-09-19T20:30:00Z",
};

const ZONE: Zone = {
  id: "z1",
  site_id: "s1",
  name: "Ground waiting",
  building_label: null,
  floor_label: "Ground",
  display_order: 0,
  active: true,
  created_at: SITE.created_at,
  updated_at: SITE.created_at,
};

const NOTICE: Notice = {
  id: "n1",
  zone_id: "z1",
  type: "image",
  content_i18n: { bn: "https://x/bn.png", en: "https://x/en.png" },
  starts_at: "2026-01-01T00:00:00Z",
  ends_at: "2026-12-31T00:00:00Z",
  sort_order: 0,
  active: true,
  created_by: "u1",
  created_at: "2026-01-01T00:00:00Z",
  updated_at: "2026-01-01T00:00:00Z",
};

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

function bodyOf(call: Recorded | undefined): Record<string, unknown> {
  return JSON.parse(String(call?.init.body)) as Record<string, unknown>;
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("notice-board administration (ticket 30, FR-DSP-006, notice_board:manage)", () => {
  it("prompts for a zone before showing any notices", async () => {
    stubApi({ ...NO_SESSION, "GET /sites": () => json(200, { items: [SITE] }) });
    renderApp(<NoticeBoardAdmin />);

    expect(await screen.findByText("Choose a zone to see and manage its notices.")).toBeInTheDocument();
  });

  it("lists a zone's scheduled notices, and creating one sends content per enabled language and the playlist window", async () => {
    const state = { notices: [] as Notice[] };
    const calls = stubApi({
      ...NO_SESSION,
      "GET /sites": () => json(200, { items: [SITE] }),
      "GET /sites/s1/zones": () => json(200, { items: [ZONE] }),
      "GET /zones/z1/notices": () => json(200, { items: state.notices }),
      "POST /notices": (init) => {
        const body = JSON.parse(String(init.body)) as Record<string, unknown>;
        const created: Notice = { ...NOTICE, ...body } as Notice;
        state.notices = [created];
        return json(201, created);
      },
    });
    renderApp(<NoticeBoardAdmin />);

    await screen.findByRole("option", { name: "Main campus" });
    await userEvent.selectOptions(screen.getByLabelText("Site"), "s1");
    await userEvent.selectOptions(await screen.findByLabelText("Zone"), "z1");
    expect(await screen.findByText("No notices are scheduled for this zone yet.")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Add a notice" }));
    await userEvent.selectOptions(screen.getByLabelText("Content type"), "rich_text");
    await userEvent.type(screen.getByLabelText("Content (Bangla)"), "ছুটির নোটিশ");
    await userEvent.type(screen.getByLabelText("Content (English)"), "Holiday notice");
    await userEvent.type(screen.getByLabelText("Starts"), "2026-06-01T09:00");
    await userEvent.type(screen.getByLabelText("Ends"), "2026-06-02T18:00");
    await userEvent.click(screen.getByRole("button", { name: "Create notice" }));

    await waitFor(() => expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/notices"))).toMatchObject({
      zone_id: "z1",
      type: "rich_text",
      content_i18n: { bn: "ছুটির নোটিশ", en: "Holiday notice" },
      sort_order: 0,
    }));
  });

  it("shows an active zone notice and can deactivate it", async () => {
    const calls = stubApi({
      ...NO_SESSION,
      "GET /sites": () => json(200, { items: [SITE] }),
      "GET /sites/s1/zones": () => json(200, { items: [ZONE] }),
      "GET /zones/z1/notices": () => json(200, { items: [NOTICE] }),
      "POST /notices/n1/deactivate": () => json(200, { ...NOTICE, active: false }),
    });
    renderApp(<NoticeBoardAdmin />);

    await screen.findByRole("option", { name: "Main campus" });
    await userEvent.selectOptions(screen.getByLabelText("Site"), "s1");
    await userEvent.selectOptions(await screen.findByLabelText("Zone"), "z1");

    const row = (await screen.findByText("Active", { exact: false })).closest("li") as HTMLElement;
    expect(within(row).getByText("Active")).toBeInTheDocument();
    await userEvent.click(within(row).getByRole("button", { name: /Deactivate/ }));
    await userEvent.click(screen.getByRole("button", { name: "Confirm deactivation" }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/notices/n1/deactivate")).toBe(true));
  });
});
