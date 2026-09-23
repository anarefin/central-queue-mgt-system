import type { PeakHoursResponse, ServiceGroup, Site, StaffingGapResponse, UserPage } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { ReportsAdmin } from "./ReportsAdmin";

const router = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const STAMP = "2026-09-19T20:30:00Z";
const SITE: Site = {
  id: "s1",
  name: "Main campus",
  code: "MAIN",
  timezone: "Asia/Dhaka",
  address: "1 Campus Road",
  default_language: "en",
  enabled_languages: ["en", "bn"],
  active: true,
  clinical_sensitivity: false,
  created_at: STAMP,
  updated_at: STAMP,
};
const GROUP: ServiceGroup = {
  id: "g1",
  site_id: "s1",
  name_i18n: { en: "Outpatient", bn: "বহির্বিভাগ" },
  missing_translations: [],
  token_prefix: "OPD",
  display_order: 1,
  active: true,
  created_at: STAMP,
  updated_at: STAMP,
};
const USERS: UserPage = { items: [{ id: "u1", username: "asha", display_name: "Asha", active: true }], next_cursor: null };
const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

function fakeApi(extra: Routes = {}): Recorded[] {
  return stubApi({
    ...NO_SESSION,
    "GET /sites": () => json(200, { items: [SITE] }),
    "GET /sites/s1/zones": () => json(200, { items: [] }),
    "GET /sites/s1/service-groups": () => json(200, { items: [GROUP] }),
    "GET /service-groups/g1/services": () => json(200, { items: [] }),
    "GET /priority-classes": () => json(200, { items: [] }),
    "GET /users?limit=200": () => json(200, USERS),
    ...extra,
  });
}

function peakHoursResponse(): PeakHoursResponse {
  return {
    key: "peak-hours",
    generated_at: STAMP,
    from: "2026-09-19T00:00:00Z",
    to: "2026-09-26T00:00:00Z",
    cells: [
      { day_of_week: 3, hour_of_day: 8, ticket_count: 2 },
      { day_of_week: 4, hour_of_day: 14, ticket_count: 1 },
    ],
  };
}

function staffingGapResponse(): StaffingGapResponse {
  const rows: StaffingGapResponse["rows"] = Array.from({ length: 24 }, (_, hour) => ({ hour_of_day: hour, tickets_offered: 0, counter_hours_available: 0, sla_attainment_pct: null }));
  rows[8] = { hour_of_day: 8, tickets_offered: 1, counter_hours_available: 0.5, sla_attainment_pct: 100.0 };
  return { key: "staffing-gap", generated_at: STAMP, from: "2026-09-19T00:00:00Z", to: "2026-09-20T00:00:00Z", rows };
}

async function cardFor(name: string) {
  const heading = await screen.findByRole("heading", { name });
  return within(heading.closest("section") as HTMLElement);
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("staffing planning views (SRS §16.2, ticket 51)", () => {
  it("blocks the run when From is after To, showing an inline error (ticket 66)", async () => {
    const calls = fakeApi({ "POST /reports/peak-hours/run": () => json(200, peakHoursResponse()) });
    renderApp(<ReportsAdmin />);

    const card = await cardFor("Staffing planning views");
    await userEvent.type(card.getByLabelText("From"), "2026-09-26");
    const to = card.getByLabelText("To");
    await userEvent.type(to, "2026-09-19");
    await userEvent.click(card.getByRole("button", { name: "Run planning view" }));

    expect(await card.findByText("The From date must be on or before the To date.")).toBeInTheDocument();
    expect(to).toHaveFocus();
    expect(calls.some((c) => c.method === "POST" && c.path === "/reports/peak-hours/run")).toBe(false);
  });

  it("is disabled until a from and to date are chosen, then runs and shows the peak-hours grid", async () => {
    const calls = fakeApi({ "POST /reports/peak-hours/run": () => json(200, peakHoursResponse()) });
    renderApp(<ReportsAdmin />);

    const card = await cardFor("Staffing planning views");
    expect(card.getByRole("button", { name: "Run planning view" })).toBeDisabled();
    expect(card.getByText("Choose a from and to date to run this view.")).toBeInTheDocument();

    await userEvent.type(card.getByLabelText("From"), "2026-09-19");
    await userEvent.type(card.getByLabelText("To"), "2026-09-26");
    expect(card.getByRole("button", { name: "Run planning view" })).toBeEnabled();
    await userEvent.click(card.getByRole("button", { name: "Run planning view" }));

    const table = await card.findByRole("table");
    const rows = within(table).getAllByRole("row");
    // rows[0] is the header row, rows[1 + hour] is that hour's row; columns are Mon..Sun, so Wed is index 2.
    const hourEightCells = within(rows.at(1 + 8) as HTMLElement).getAllByRole("cell");
    const hourFourteenCells = within(rows.at(1 + 14) as HTMLElement).getAllByRole("cell");
    expect(hourEightCells.at(2)).toHaveTextContent("2"); // Wednesday, 08:00 -> 2 tickets
    expect(hourFourteenCells.at(3)).toHaveTextContent("1"); // Thursday, 14:00 -> 1 ticket

    const run = calls.find((c) => c.method === "POST" && c.path === "/reports/peak-hours/run");
    expect(run).toBeDefined();
    expect(JSON.parse(String(run?.init.body))).toMatchObject({ site_id: "s1", from: "2026-09-19T00:00:00.000Z", to: "2026-09-26T00:00:00.000Z" });
  });

  it("switches to staffing-gap and shows tickets offered, counter-hours and SLA attainment per hour band", async () => {
    fakeApi({ "POST /reports/staffing-gap/run": () => json(200, staffingGapResponse()) });
    renderApp(<ReportsAdmin />);

    const card = await cardFor("Staffing planning views");
    await userEvent.selectOptions(card.getByLabelText("View"), "Staffing-gap view");
    await userEvent.type(card.getByLabelText("From"), "2026-09-19");
    await userEvent.type(card.getByLabelText("To"), "2026-09-20");
    await userEvent.click(card.getByRole("button", { name: "Run planning view" }));

    const table = await card.findByRole("table");
    expect(within(table).getByText("100.0%")).toBeInTheDocument();
    expect(within(table).getByText("0.5")).toBeInTheDocument();
  });

  it("is in Bangla too", async () => {
    fakeApi({ "POST /reports/peak-hours/run": () => json(200, peakHoursResponse()) });
    renderApp(<ReportsAdmin />, ["bn-BD"]);

    const card = await cardFor("জনবল পরিকল্পনা দৃশ্য");
    await userEvent.type(card.getByLabelText("শুরু"), "2026-09-19");
    await userEvent.type(card.getByLabelText("শেষ"), "2026-09-26");
    await userEvent.click(card.getByRole("button", { name: "পরিকল্পনা দৃশ্য চালান" }));

    await waitFor(() => expect(card.getAllByText("2").length).toBeGreaterThan(0));
  });
});
