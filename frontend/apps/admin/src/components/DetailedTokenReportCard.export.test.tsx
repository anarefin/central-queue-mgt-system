import type { DetailedTokenReportPage, ServiceGroup, Site, UserPage, Zone } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
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

function page(): DetailedTokenReportPage {
  return { key: "detailed-token", generated_at: STAMP, page: 0, size: 50, total_rows: 0, total_pages: 0, tickets_issued: 0, rows: [] };
}

function fileResponse(content: string, contentType: string, filename: string): Response {
  return new Response(content, { status: 200, headers: { "Content-Type": contentType, "Content-Disposition": `attachment; filename="${filename}"` } });
}

function fakeApi(extra: Routes = {}): Recorded[] {
  return stubApi({
    ...NO_SESSION,
    "GET /sites": () => json(200, { items: [SITE] }),
    "GET /sites/s1/zones": () => json(200, { items: [ZONE] }),
    "GET /sites/s1/service-groups": () => json(200, { items: [GROUP] }),
    "GET /service-groups/g1/services": () => json(200, { items: [] }),
    "GET /priority-classes": () => json(200, { items: [] }),
    "GET /users?limit=200": () => json(200, USERS),
    "POST /reports/detailed-token/run": () => json(200, page()),
    ...extra,
  });
}

let clicked: string[];

beforeEach(() => {
  clicked = [];
  // jsdom does not implement the Blob-URL API at all; downloadBlob() needs both.
  URL.createObjectURL = vi.fn(() => "blob:mock");
  URL.revokeObjectURL = vi.fn();
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
    clicked.push(this.download);
  });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("report export (ticket 49, FR-RPT-003/004)", () => {
  it("downloads the file directly when the export is generated inline", async () => {
    const calls = fakeApi({
      "POST /reports/detailed-token/export": () => fileResponse("csv,content", "text/csv", "detailed-token.csv"),
    });
    renderApp(<ReportsAdmin />);

    await screen.findByRole("heading", { name: "Detailed token report" });
    await userEvent.click(screen.getByRole("button", { name: "Export" }));

    await waitFor(() => expect(clicked).toContain("detailed-token.csv"));
    const exportCall = calls.find((c) => c.method === "POST" && c.path === "/reports/detailed-token/export");
    expect(JSON.parse(String(exportCall?.init.body))).toMatchObject({ site_id: "s1", format: "csv" });
  });

  it("polls the job and downloads once a background export finishes", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let jobCalls = 0;
    fakeApi({
      "POST /reports/detailed-token/export": () => json(202, { id: "job-1", status: "queued" }),
      "GET /reports/jobs/job-1": () => {
        jobCalls += 1;
        return jobCalls < 2
          ? json(200, { id: "job-1", report_key: "detailed-token", format: "csv", status: "queued", row_count: null, requested_at: STAMP, completed_at: null, expires_at: null, error: null })
          : json(200, { id: "job-1", report_key: "detailed-token", format: "csv", status: "done", row_count: 2, requested_at: STAMP, completed_at: STAMP, expires_at: STAMP, error: null });
      },
      "GET /reports/jobs/job-1/download": () => fileResponse("csv,content", "text/csv", "detailed-token.csv"),
    });
    renderApp(<ReportsAdmin />);

    await screen.findByRole("heading", { name: "Detailed token report" });
    await userEvent.click(screen.getByRole("button", { name: "Export" }));

    expect(await screen.findByText(/Preparing your export/)).toBeInTheDocument();

    await vi.advanceTimersByTimeAsync(2100);
    await vi.advanceTimersByTimeAsync(2100);

    await waitFor(() => expect(clicked).toContain("detailed-token.csv"));
    expect(await screen.findByText(/Your export is ready/)).toBeInTheDocument();
  });

  it("lets the caller choose xlsx or pdf", async () => {
    const calls = fakeApi({
      "POST /reports/detailed-token/export": () => fileResponse("pdf-bytes", "application/pdf", "detailed-token.pdf"),
    });
    renderApp(<ReportsAdmin />);

    await screen.findByRole("heading", { name: "Detailed token report" });
    await userEvent.selectOptions(screen.getByLabelText("Export format"), "pdf");
    await userEvent.click(screen.getByRole("button", { name: "Export" }));

    await waitFor(() => expect(clicked).toContain("detailed-token.pdf"));
    const exportCall = calls.find((c) => c.method === "POST" && c.path === "/reports/detailed-token/export");
    expect(JSON.parse(String(exportCall?.init.body))).toMatchObject({ format: "pdf" });
  });
});
