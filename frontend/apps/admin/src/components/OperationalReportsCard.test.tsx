import type { OperationalReportResponse, ServiceGroup, Site, UserPage, Zone } from "@qms/api-client";
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
const ZONE: Zone = { id: "z1", site_id: "s1", name: "Hall", building_label: null, floor_label: "1st", display_order: 1, active: true, created_at: STAMP, updated_at: STAMP };
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
    "GET /sites/s1/zones": () => json(200, { items: [ZONE] }),
    "GET /sites/s1/service-groups": () => json(200, { items: [GROUP] }),
    "GET /service-groups/g1/services": () => json(200, { items: [] }),
    "GET /priority-classes": () => json(200, { items: [] }),
    "GET /users?limit=200": () => json(200, USERS),
    ...extra,
  });
}

function visitorFlowResponse(): OperationalReportResponse {
  return {
    key: "visitor-flow",
    generated_at: STAMP,
    from: "2026-09-19T00:00:00Z",
    to: "2026-09-20T00:00:00Z",
    previous_from: "2026-09-18T00:00:00Z",
    previous_to: "2026-09-19T00:00:00Z",
    rows: [{ bucket: "2026-09-19T00:00:00Z", issued: 12, served: 10, cancelled: 1, no_show: 1, peak_concurrent_waiting: 4 }],
    totals: { issued: 12, served: 10, cancelled: 1, no_show: 1, peak_concurrent_waiting: 4 },
    previous_totals: { issued: 10, served: 9, cancelled: 1, no_show: 0, peak_concurrent_waiting: 3 },
    change: {
      issued: { absolute: 2, percent: 20 },
      served: { absolute: 1, percent: 11.1 },
      cancelled: { absolute: 0, percent: 0 },
      no_show: { absolute: 1, percent: null },
      peak_concurrent_waiting: { absolute: 1, percent: 33.3 },
    },
    extra: null,
  };
}

async function cardFor(name: string) {
  const heading = await screen.findByRole("heading", { name });
  return within(heading.closest("section") as HTMLElement);
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("operational reports (SRS §16.1, §15.2, §15.3, ticket 50)", () => {
  it("blocks the run when From is after To, showing an inline error (ticket 66)", async () => {
    const calls = fakeApi({ "POST /reports/visitor-flow/run": () => json(200, visitorFlowResponse()) });
    renderApp(<ReportsAdmin />);

    const card = await cardFor("Operational reports");
    await userEvent.type(card.getByLabelText("From"), "2026-09-20");
    const to = card.getByLabelText("To");
    await userEvent.type(to, "2026-09-01");
    await userEvent.click(card.getByRole("button", { name: "Run operational report" }));

    expect(await card.findByText("The From date must be on or before the To date.")).toBeInTheDocument();
    expect(to).toHaveFocus();
    expect(calls.some((c) => c.method === "POST" && c.path === "/reports/visitor-flow/run")).toBe(false);
  });

  it("is disabled until a from and to date are chosen, then runs and shows rows and the period comparison", async () => {
    const calls = fakeApi({ "POST /reports/visitor-flow/run": () => json(200, visitorFlowResponse()) });
    renderApp(<ReportsAdmin />);

    const card = await cardFor("Operational reports");
    expect(card.getByRole("button", { name: "Run operational report" })).toBeDisabled();
    expect(card.getByText("Choose a from and to date to run this report.")).toBeInTheDocument();

    await userEvent.type(card.getByLabelText("From"), "2026-09-19");
    await userEvent.type(card.getByLabelText("To"), "2026-09-20");
    expect(card.getByRole("button", { name: "Run operational report" })).toBeEnabled();
    await userEvent.click(card.getByRole("button", { name: "Run operational report" }));

    const tables = await waitFor(() => {
      const found = card.getAllByRole("table");
      expect(found).toHaveLength(2);
      return found;
    });
    const [rowsTable, totalsTable] = tables as [HTMLElement, HTMLElement];
    expect(within(rowsTable).getByText("12")).toBeInTheDocument(); // this bucket's issued count
    expect(within(totalsTable).getByText("+2 (+20.0%)")).toBeInTheDocument(); // the change row for "issued"

    const run = calls.find((c) => c.method === "POST" && c.path === "/reports/visitor-flow/run");
    expect(run).toBeDefined();
    expect(JSON.parse(String(run?.init.body))).toMatchObject({ site_id: "s1", from: "2026-09-19T00:00:00.000Z", to: "2026-09-20T00:00:00.000Z", grain: "day" });
  });

  it("switches report key and re-labels the report-specific columns", async () => {
    fakeApi({
      "POST /reports/agent/run": () =>
        json(200, {
          key: "agent",
          generated_at: STAMP,
          from: "2026-09-19T00:00:00Z",
          to: "2026-09-20T00:00:00Z",
          previous_from: "2026-09-18T00:00:00Z",
          previous_to: "2026-09-19T00:00:00Z",
          rows: [
            {
              agent_id: "u1",
              agent_name: "Asha",
              services_served: 5,
              services_cancelled: 1,
              avg_wait_seconds: 120,
              avg_service_seconds: 180,
              total_service_seconds: 900,
              avg_break_seconds: 600,
              login_adherence_pct: 42.5,
              successful_token_rate_pct: 83.3,
            },
          ],
          totals: {
            services_served: 5,
            services_cancelled: 1,
            avg_wait_seconds: 120,
            avg_service_seconds: 180,
            total_service_seconds: 900,
            avg_break_seconds: 600,
            login_adherence_pct: 42.5,
            successful_token_rate_pct: 90.0,
          },
          previous_totals: {
            services_served: 4,
            services_cancelled: 0,
            avg_wait_seconds: 100,
            avg_service_seconds: 150,
            total_service_seconds: 600,
            avg_break_seconds: 500,
            login_adherence_pct: 40,
            successful_token_rate_pct: 100,
          },
          change: {
            services_served: { absolute: 1, percent: 25 },
            services_cancelled: { absolute: 1, percent: null },
            avg_wait_seconds: { absolute: 20, percent: 20 },
            avg_service_seconds: { absolute: 30, percent: 20 },
            total_service_seconds: { absolute: 300, percent: 50 },
            avg_break_seconds: { absolute: 100, percent: 20 },
            login_adherence_pct: { absolute: 2.5, percent: 6.25 },
            successful_token_rate_pct: { absolute: -16.7, percent: -16.7 },
          },
          extra: null,
        } satisfies OperationalReportResponse),
    });
    renderApp(<ReportsAdmin />);

    const card = await cardFor("Operational reports");
    await userEvent.selectOptions(card.getByLabelText("Report"), "Agent report");
    await userEvent.type(card.getByLabelText("From"), "2026-09-19");
    await userEvent.type(card.getByLabelText("To"), "2026-09-20");
    await userEvent.click(card.getByRole("button", { name: "Run operational report" }));

    const rowHeader = await card.findByRole("rowheader", { name: "Asha" });
    const rowsTable = rowHeader.closest("table") as HTMLElement;
    expect(within(rowsTable).getByRole("columnheader", { name: "Successful token rate" })).toBeInTheDocument();
    expect(within(rowsTable).getByText("83.3%")).toBeInTheDocument();
  });

  it("is in Bangla too", async () => {
    fakeApi({ "POST /reports/visitor-flow/run": () => json(200, visitorFlowResponse()) });
    renderApp(<ReportsAdmin />, ["bn-BD"]);

    const card = await cardFor("কার্যক্রম প্রতিবেদন");
    await userEvent.type(card.getByLabelText("শুরু"), "2026-09-19");
    await userEvent.type(card.getByLabelText("শেষ"), "2026-09-20");
    await userEvent.click(card.getByRole("button", { name: "কার্যক্রম প্রতিবেদন চালান" }));

    await waitFor(() => expect(card.getAllByText("12").length).toBeGreaterThan(0));
  });
});
