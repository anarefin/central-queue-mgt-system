import type { DetailedTokenReportPage, DetailedTokenReportRequest, PriorityClass, ServiceGroup, Site, UserPage, Zone } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import Home from "../app/page";
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
const NORMAL: PriorityClass = {
  id: "c0",
  name_i18n: { en: "Normal", bn: "সাধারণ" },
  headstart_minutes: 0,
  max_wait_minutes: null,
  token_prefix_override: null,
  is_default: true,
  active: true,
  created_at: STAMP,
  updated_at: STAMP,
};
const USERS: UserPage = { items: [{ id: "u1", username: "asha", display_name: "Asha", active: true }], next_cursor: null };

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function row(over: Partial<DetailedTokenReportPage["rows"][number]> = {}): DetailedTokenReportPage["rows"][number] {
  return {
    ticket_id: "t1",
    token: "OPD-001",
    visitor_code: "V-1",
    visitor_name: "Karim",
    visitor_category: "senior",
    service_group: { en: "Outpatient", bn: "বহির্বিভাগ" },
    service: { en: "Consultation", bn: "পরামর্শ" },
    channel: "reception",
    priority_class: { en: "Normal", bn: "সাধারণ" },
    issue_time: "2026-09-19T10:00:00Z",
    call_time: "2026-09-19T10:02:00Z",
    start_time: "2026-09-19T10:03:00Z",
    end_time: "2026-09-19T10:08:00Z",
    wait_seconds: 120,
    service_seconds: 300,
    counter: "1",
    agent: "Asha",
    outcome: { en: "Resolved", bn: "সমাধান হয়েছে" },
    transfers: 0,
    ...over,
  };
}

function page(over: Partial<DetailedTokenReportPage> = {}): DetailedTokenReportPage {
  return {
    key: "detailed-token",
    generated_at: STAMP,
    page: 0,
    size: 50,
    total_rows: 1,
    total_pages: 1,
    tickets_issued: 1,
    rows: [row()],
    ...over,
  };
}

function fakeApi(extra: Routes = {}): Recorded[] {
  return stubApi({
    ...NO_SESSION,
    "GET /sites": () => json(200, { items: [SITE] }),
    "GET /sites/s1/zones": () => json(200, { items: [ZONE] }),
    "GET /sites/s1/service-groups": () => json(200, { items: [GROUP] }),
    "GET /service-groups/g1/services": () => json(200, { items: [] }),
    "GET /priority-classes": () => json(200, { items: [NORMAL] }),
    "GET /users?limit=200": () => json(200, USERS),
    ...extra,
  });
}

function bodyOf(call: Recorded | undefined): DetailedTokenReportRequest {
  return JSON.parse(String(call?.init.body)) as DetailedTokenReportRequest;
}

describe("detailed token report (SRS §16.1, ticket 48)", () => {
  it("runs with the chosen filters and shows the summary and table", async () => {
    const calls = fakeApi({ "POST /reports/detailed-token/run": () => json(200, page()) });
    renderApp(<ReportsAdmin />);

    const heading = await screen.findByRole("heading", { name: "Detailed token report" });
    // Ticket 50 adds a second card (operational reports) to this same page, with its own "Zone"/"Visitor category"
    // fields — scoped to the detailed token report's own <section> (@qms/ui's Card) so this queries only its own.
    const card = within(heading.closest("section") as HTMLElement);
    await card.findByText("Hall");
    await userEvent.selectOptions(card.getByLabelText("Zone"), "Hall");
    await userEvent.type(card.getByLabelText("Visitor category"), "senior");
    await userEvent.click(card.getByRole("button", { name: "Run report" }));

    expect(await screen.findByRole("status")).toHaveTextContent("1 rows, 1 tickets issued");
    const table = screen.getByRole("table");
    expect(within(table).getAllByRole("row")).toHaveLength(2); // header + one data row
    expect(within(table).getByText("OPD-001")).toBeInTheDocument();
    expect(within(table).getByText("Karim")).toBeInTheDocument();
    expect(within(table).getByText("Resolved")).toBeInTheDocument();

    const run = calls.find((c) => c.method === "POST" && c.path === "/reports/detailed-token/run");
    expect(bodyOf(run)).toMatchObject({ site_id: "s1", zone_id: "z1", visitor_category: "senior", page: 0, size: 50, sort: "issued_at", direction: "desc" });
  });

  it("sorts by clicking a column header, toggling direction on a second click", async () => {
    const calls = fakeApi({ "POST /reports/detailed-token/run": () => json(200, page()) });
    renderApp(<ReportsAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Run report" }));
    await screen.findByRole("status");

    await userEvent.click(screen.getByRole("button", { name: /^Token/ }));
    await waitFor(() => expect(bodyOf(calls.filter((c) => c.method === "POST").at(-1))).toMatchObject({ sort: "token_number", direction: "asc" }));

    await userEvent.click(screen.getByRole("button", { name: /^Token/ }));
    await waitFor(() => expect(bodyOf(calls.filter((c) => c.method === "POST").at(-1))).toMatchObject({ sort: "token_number", direction: "desc" }));
  });

  it("pages through more rows than one page holds", async () => {
    const calls = fakeApi({ "POST /reports/detailed-token/run": () => json(200, page({ total_rows: 60, total_pages: 2 })) });
    renderApp(<ReportsAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Run report" }));
    await screen.findByRole("status");

    expect(screen.getByRole("button", { name: "Previous page" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Next page" }));
    await waitFor(() => expect(bodyOf(calls.filter((c) => c.method === "POST").at(-1))).toMatchObject({ page: 1 }));
  });

  it("says nobody matches the filter, and shows a refusal from the API", async () => {
    let allowed = true;
    fakeApi({
      "POST /reports/detailed-token/run": () =>
        allowed ? json(200, page({ total_rows: 0, tickets_issued: 0, rows: [] })) : json(403, { error: { code: "forbidden", message: "x", trace_id: "t" } }),
    });
    renderApp(<ReportsAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Run report" }));
    expect(await screen.findByText("No rows match this filter.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();

    allowed = false;
    await userEvent.click(screen.getByRole("button", { name: "Run report" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("You do not have permission to do this.");
  });

  it("is in Bangla too", async () => {
    fakeApi({ "POST /reports/detailed-token/run": () => json(200, page()) });
    renderApp(<ReportsAdmin />, ["bn-BD"]);

    expect(await screen.findByText("বিস্তারিত টোকেন প্রতিবেদন")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "প্রতিবেদন চালান" }));
    expect(await screen.findByRole("status")).toHaveTextContent("1টি সারি, 1টি টিকিট ইস্যু হয়েছে");
  });

  it("links to the screen for an administrator only; the API enforces access either way", async () => {
    const home = (roles: string[]): Routes => ({
      "POST /auth/refresh": () => json(200, { access_token: "tok", token_type: "Bearer", expires_in: 900 }),
      "GET /auth/me": () => json(200, { id: "u1", username: "asha", display_name: "Asha", preferred_language: null, roles, sites: ["s1"], groups: [] }),
      "GET /health/dependencies": () => json(200, { status: "up", dependencies: {} }),
    });
    stubApi(home(["org_admin"]));
    const admin = renderApp(<Home />);
    const link = await screen.findByRole("link", { name: "Reports" });
    expect(link.getAttribute("href")).toMatch(/^\/reports\/?$/);
    admin.unmount();

    stubApi(home(["reception_operator"]));
    renderApp(<Home />);
    await screen.findByRole("link", { name: "Reception desk" });
    expect(screen.queryByRole("link", { name: "Reports" })).not.toBeInTheDocument();
  });
});
