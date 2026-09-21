import type { ReportSchedule, ReportScheduleDelivery, Site } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { ReportScheduleCard } from "./ReportScheduleCard";

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

const SITE: Site = {
  id: "s1",
  name: "Main campus",
  code: "MAIN",
  timezone: "Asia/Dhaka",
  address: "1 Campus Road",
  default_language: "en",
  enabled_languages: ["en", "bn"],
  active: true,
  created_at: "2026-09-19T10:00:00Z",
  updated_at: "2026-09-19T10:00:00Z",
};

function schedule(overrides: Partial<ReportSchedule> = {}): ReportSchedule {
  return {
    id: "sch-1",
    report_key: "detailed-token",
    cadence: "daily",
    format: "csv",
    recipients: ["ops@example.com"],
    filter: { site_id: "s1" },
    enabled: true,
    created_by: "u1",
    created_at: "2026-09-19T10:00:00Z",
    updated_at: "2026-09-19T10:00:00Z",
    next_run_at: "2026-09-20T10:00:00Z",
    last_run_at: null,
    ...overrides,
  };
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

/** An in-memory API, so a create/update/delete is visible on the next list read the screen makes. */
function fakeApi(state: { schedules: ReportSchedule[]; deliveries?: ReportScheduleDelivery[] }, extra: Routes = {}): Recorded[] {
  return stubApi({
    ...NO_SESSION,
    "GET /reports/schedules": () => json(200, { items: state.schedules }),
    "POST /reports/schedules": (init) => {
      const created = schedule({ id: `sch-${state.schedules.length + 1}`, ...(JSON.parse(String(init.body)) as Partial<ReportSchedule>) });
      state.schedules = [...state.schedules, created];
      return json(200, created);
    },
    "PUT /reports/schedules/sch-1": (init) => {
      const updated: ReportSchedule = { ...state.schedules[0]!, ...(JSON.parse(String(init.body)) as Partial<ReportSchedule>) };
      state.schedules = [updated, ...state.schedules.slice(1)];
      return json(200, updated);
    },
    "DELETE /reports/schedules/sch-1": () => {
      state.schedules = state.schedules.filter((s) => s.id !== "sch-1");
      return json(204, undefined);
    },
    "GET /reports/schedules/sch-1/deliveries": () => json(200, { items: state.deliveries ?? [] }),
    ...extra,
  });
}

describe("scheduled report delivery (SRS §16, FR-RPT-005, ticket 52)", () => {
  it("shows no schedules yet, then the one just created", async () => {
    const calls = fakeApi({ schedules: [] });
    renderApp(<ReportScheduleCard site={SITE} />);

    expect(await screen.findByText("No scheduled reports yet.")).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText("How often"), "weekly");
    await userEvent.selectOptions(screen.getByLabelText("Format"), "xlsx");
    await userEvent.type(screen.getByLabelText("Recipients"), "ops@example.com\nsecond@example.com");
    await userEvent.click(screen.getByRole("button", { name: "Add schedule" }));

    const row = await screen.findByRole("listitem");
    expect(within(row).getByText("detailed-token")).toBeInTheDocument();
    const created = calls.find((c) => c.method === "POST" && c.path === "/reports/schedules");
    expect(JSON.parse(String(created?.init.body))).toEqual({
      report_key: "detailed-token",
      cadence: "weekly",
      format: "xlsx",
      recipients: ["ops@example.com", "second@example.com"],
      filter: { site_id: "s1" },
    });
  });

  it("toggles a schedule disabled, then re-enables it", async () => {
    fakeApi({ schedules: [schedule()] });
    renderApp(<ReportScheduleCard site={SITE} />);

    const row = within(await screen.findByRole("listitem"));
    expect(row.getByText("Enabled")).toBeInTheDocument();

    await userEvent.click(row.getByRole("button", { name: "Disable" }));

    await waitFor(() => expect(row.getByText("Disabled")).toBeInTheDocument());
    expect(row.getByRole("button", { name: "Enable" })).toBeInTheDocument();
  });

  it("deletes a schedule once the confirmation is accepted", async () => {
    vi.stubGlobal("confirm", () => true);
    fakeApi({ schedules: [schedule()] });
    renderApp(<ReportScheduleCard site={SITE} />);

    const row = within(await screen.findByRole("listitem"));
    await userEvent.click(row.getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(screen.getByText("No scheduled reports yet.")).toBeInTheDocument());
  });

  it("shows the delivery log, including a failure with no recipient", async () => {
    fakeApi({
      schedules: [schedule()],
      deliveries: [
        { id: "d1", run_at: "2026-09-20T10:00:00Z", recipient: "ops@example.com", status: "sent", row_count: 5, error: null, attempted_at: "2026-09-20T10:00:01Z" },
        { id: "d2", run_at: "2026-09-19T10:00:00Z", recipient: null, status: "failed", row_count: null, error: "boom", attempted_at: "2026-09-19T10:00:01Z" },
      ],
    });
    renderApp(<ReportScheduleCard site={SITE} />);

    const row = within(await screen.findByRole("listitem"));
    await userEvent.click(row.getByRole("button", { name: "Show delivery log" }));

    expect(await row.findByText("Sent to ops@example.com")).toBeInTheDocument();
    expect(row.getByText("The report itself failed to generate: boom")).toBeInTheDocument();
  });
});
