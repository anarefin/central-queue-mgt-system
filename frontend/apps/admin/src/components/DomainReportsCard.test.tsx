import type { AuditReportPage, DomainReportPage, ServiceGroup, Site, UserPage } from "@qms/api-client";
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

function appointmentPage(): DomainReportPage {
  return {
    key: "appointment",
    generated_at: STAMP,
    page: 0,
    size: 50,
    total_rows: 1,
    total_pages: 1,
    rows: [
      {
        appointment_id: "a1",
        reference_code: "APT-0001",
        service_id: "sv1",
        service_name: { en: "Consultation" },
        agent_id: "u1",
        agent_name: "Asha",
        visitor_category: "vip",
        source: "phone",
        state: "converted",
        booked_at: "2026-09-18T09:00:00Z",
        checked_in_at: "2026-09-20T09:05:00Z",
        checkin_variance_seconds: 300,
        lead_time_seconds: 172800,
        no_show: false,
      },
    ],
    extra: {
      no_show_rate_by_service: [{ id: "sv1", name: { en: "Consultation" }, total: 2, no_show: 1, no_show_rate_pct: 50.0 }],
      no_show_rate_by_agent: [{ id: "u1", name: "Asha", total: 2, no_show: 1, no_show_rate_pct: 50.0 }],
      no_show_rate_by_visitor_category: [{ id: "vip", name: "vip", total: 1, no_show: 0, no_show_rate_pct: 0.0 }],
      adherence: { resolved: 2, shown: 1, adherence_pct: 50.0 },
    },
  };
}

function notificationPage(): DomainReportPage {
  return {
    key: "notification",
    generated_at: STAMP,
    page: 0,
    size: 50,
    total_rows: 1,
    total_pages: 1,
    rows: [
      {
        id: "n1",
        trigger_key: "ticket.called",
        channel: "email",
        status: "sent",
        cost_indicator: "low",
        site_id: "s1",
        site_name: "Main campus",
        service_id: "sv1",
        service_name: { en: "Consultation" },
        created_at: "2026-09-19T09:00:00Z",
        sent_at: "2026-09-19T09:01:00Z",
      },
    ],
    extra: null,
  };
}

function auditPage(): AuditReportPage {
  return {
    key: "audit",
    generated_at: STAMP,
    items: [
      {
        id: "ev1",
        actor_id: "u1",
        actor_role: "org_admin",
        action: "user.created",
        entity: "user",
        entity_id: "u2",
        before: null,
        after: null,
        ip: "1.2.3.4",
        device: "chrome",
        reason: null,
        trace_id: "t1",
        occurred_at: "2026-09-19T09:00:00Z",
      },
    ],
    next_cursor: null,
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

describe("domain reports (SRS §16.1, ticket 51)", () => {
  it("runs the appointment report and shows its rows and no-show/adherence extras", async () => {
    const calls = fakeApi({ "POST /reports/appointment/run": () => json(200, appointmentPage()) });
    renderApp(<ReportsAdmin />);

    const card = await cardFor("Domain reports");
    await userEvent.click(card.getByRole("button", { name: "Run domain report" }));

    await waitFor(() => expect(card.getByText("APT-0001")).toBeInTheDocument());
    expect(card.getByText("No-show rate by service")).toBeInTheDocument();
    expect(card.getByText("Appointment adherence: 50.0%")).toBeInTheDocument();

    const run = calls.find((c) => c.method === "POST" && c.path === "/reports/appointment/run");
    expect(run).toBeDefined();
    expect(JSON.parse(String(run?.init.body))).toMatchObject({ site_id: "s1" });
  });

  it("blocks the run when From is after To, showing an inline error (ticket 66)", async () => {
    const calls = fakeApi({ "POST /reports/appointment/run": () => json(200, appointmentPage()) });
    renderApp(<ReportsAdmin />);

    const card = await cardFor("Domain reports");
    await userEvent.type(card.getByLabelText("From"), "2026-09-20");
    const to = card.getByLabelText("To");
    await userEvent.type(to, "2026-09-01");
    await userEvent.click(card.getByRole("button", { name: "Run domain report" }));

    expect(await card.findByText("The From date must be on or before the To date.")).toBeInTheDocument();
    expect(to).toHaveFocus();
    expect(calls.some((c) => c.method === "POST" && c.path === "/reports/appointment/run")).toBe(false);
  });

  it("switches report key to notification and re-labels the columns", async () => {
    fakeApi({ "POST /reports/notification/run": () => json(200, notificationPage()) });
    renderApp(<ReportsAdmin />);

    const card = await cardFor("Domain reports");
    await userEvent.selectOptions(card.getByLabelText("Report"), "Notification report");
    await userEvent.click(card.getByRole("button", { name: "Run domain report" }));

    expect(await card.findByRole("columnheader", { name: "Cost indicator" })).toBeInTheDocument();
    await waitFor(() => expect(card.getByText("low")).toBeInTheDocument());
  });

  it("runs the audit report and shows its own rows and a forbidden error separately", async () => {
    fakeApi({ "POST /reports/audit/run": () => json(200, auditPage()) });
    renderApp(<ReportsAdmin />);

    const card = await cardFor("Domain reports");
    await userEvent.selectOptions(card.getByLabelText("Report"), "Audit report");
    await userEvent.click(card.getByRole("button", { name: "Run domain report" }));

    await waitFor(() => expect(card.getByText("user.created")).toBeInTheDocument());
  });

  it("shows a forbidden message when audit:read is denied for a Team Admin", async () => {
    fakeApi({
      "POST /reports/audit/run": () => json(403, { error: { code: "forbidden", message: "x", trace_id: "t" } }),
    });
    renderApp(<ReportsAdmin />);

    const card = await cardFor("Domain reports");
    await userEvent.selectOptions(card.getByLabelText("Report"), "Audit report");
    await userEvent.click(card.getByRole("button", { name: "Run domain report" }));

    await waitFor(() => expect(card.getByText("You do not have permission to do this.")).toBeInTheDocument());
  });

  it("is in Bangla too", async () => {
    fakeApi({ "POST /reports/appointment/run": () => json(200, appointmentPage()) });
    renderApp(<ReportsAdmin />, ["bn-BD"]);

    const card = await cardFor("ডোমেইন প্রতিবেদন");
    await userEvent.click(card.getByRole("button", { name: "ডোমেইন প্রতিবেদন চালান" }));

    await waitFor(() => expect(card.getByText("APT-0001")).toBeInTheDocument());
  });
});
