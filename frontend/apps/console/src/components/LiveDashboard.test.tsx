import type { Alert, DashboardSnapshot } from "@qms/api-client";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import DashboardPage from "../app/dashboard/page";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";

const router = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }));
const searchParams = vi.hoisted(() => new URLSearchParams("site_id=s1"));
vi.mock("next/navigation", () => ({
  useRouter: () => router,
  useSearchParams: () => searchParams,
}));

const TOKENS = { access_token: "tok", token_type: "Bearer", expires_in: 900 };
const ME = { id: "u1", username: "sam", display_name: "Sam", preferred_language: null as string | null, roles: ["org_admin"], sites: ["s1"], groups: [] };
const AUTH = {
  "POST /auth/refresh": () => json(200, TOKENS),
  "GET /auth/me": () => json(200, ME),
  // Ticket 47's own alerts tile; the alert-specific tests below override this with their own fixtures.
  "GET /sites/s1/alerts?state=open": () => json(200, { items: [] }),
};

function alert(over: Partial<Alert> = {}): Alert {
  return {
    id: "al1",
    site_id: "s1",
    service_id: "v1",
    threshold_type: "queue_length",
    subject_id: null,
    state: "open",
    breach_count: 1,
    measured_value: 12,
    threshold_value: 10,
    first_breached_at: "2026-09-19T09:55:00Z",
    last_breached_at: "2026-09-19T09:55:00Z",
    escalated_at: null,
    acknowledged_at: null,
    acknowledged_by: null,
    acknowledgement_note: null,
    created_at: "2026-09-19T09:55:00Z",
    ...over,
  };
}

function snapshot(over: Partial<DashboardSnapshot> = {}): DashboardSnapshot {
  return {
    site_id: "s1",
    zone_id: null,
    service_group_id: null,
    service_id: null,
    priority_class_id: null,
    generated_at: "2026-09-19T10:00:00Z",
    waiting_now: [{ service_group_id: "g1", service_group_name: { en: "Outpatient" }, count: 3, longest_wait_seconds: 605 }],
    serving_now: [],
    counters: { open: 2, on_break: 1, closed: 1, idle_with_queue: 0 },
    longest_waits: [{ ticket_id: "t1", token_number: "A-001", service_id: "v1", service_name: { en: "Consultation" }, wait_seconds: 605, escalated: true, sla_breached: false }],
    throughput_today: { served: 10, cancelled: 1, no_show: 0, transferred: 0 },
    appointments_today: { booked: 2, checked_in: 1, no_show: 0, upcoming_next_hour: 1 },
    remote_queue: { remote: 1, approaching: 0, present: 0, forfeited: 0 },
    device_health: { kiosks_offline: 0, displays_offline: 0, printers_offline: null },
    served_per_open_counter: [{ counter_id: "c1", label: "Desk 1", session_id: "sess1", served_count: 4 }],
    ...over,
  };
}

beforeEach(() => {
  router.replace.mockReset();
  router.push.mockReset();
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("the live dashboard (SRS §15.1, ticket 46)", () => {
  it("loads the caller's scoped snapshot for the Site named in the URL and renders every FR-MON-003 tile", async () => {
    stubApi({ ...AUTH, "GET /dashboard/live?site_id=s1": () => json(200, snapshot()) });

    renderApp(<DashboardPage />);

    expect(await screen.findByText("Outpatient: 3 waiting, longest 10 min 5 s")).toBeInTheDocument();
    expect(screen.getByText("Open: 2")).toBeInTheDocument();
    expect(screen.getByText("On break: 1")).toBeInTheDocument();
    expect(screen.getByText("A-001 — 10 min 5 s")).toBeInTheDocument();
    expect(screen.getByText("Escalated")).toBeInTheDocument();
    expect(screen.getByText("Served: 10")).toBeInTheDocument();
    expect(screen.getByText("Booked: 2")).toBeInTheDocument();
    expect(screen.getByText("Remote: 1")).toBeInTheDocument();
    expect(screen.getByText("Kiosks offline: 0")).toBeInTheDocument();
    expect(screen.getByText("Printers offline: not tracked yet")).toBeInTheDocument();
    expect(screen.getByText("Desk 1: 4")).toBeInTheDocument();
  });

  it("sends a supervisor's own staff alert to the filtered Site (FR-MON-004)", async () => {
    const calls: Recorded[] = stubApi({
      ...AUTH,
      "GET /dashboard/live?site_id=s1": () => json(200, snapshot()),
      "POST /dashboard/s1/staff-alert": () => new Response(null, { status: 204 }),
    });
    const user = userEvent.setup();

    renderApp(<DashboardPage />);
    await screen.findByText("Open: 2");

    await user.type(screen.getByLabelText("Message to the team"), "Counter 3 needs help");
    await user.click(screen.getByRole("button", { name: "Send" }));

    await waitFor(() => expect(screen.getByText("Alert sent.")).toBeInTheDocument());
    const sent = calls.find((c) => c.method === "POST" && c.path === "/dashboard/s1/staff-alert");
    expect(JSON.parse(String(sent?.init.body))).toEqual({ message: "Counter 3 needs help" });
  });

  it("force-closes a served counter's live session from its own row (FR-MON-004)", async () => {
    const calls: Recorded[] = stubApi({
      ...AUTH,
      "GET /dashboard/live?site_id=s1": () => json(200, snapshot()),
      "POST /sessions/sess1/force-close": () => json(200, { id: "sess1" }),
    });
    const user = userEvent.setup();

    renderApp(<DashboardPage />);
    await screen.findByText("Desk 1: 4");

    await user.click(screen.getByRole("button", { name: "Force-close" }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/sessions/sess1/force-close")).toBe(true));
  });

  // ---- ticket 47: threshold alerts (SRS §15.4) ---------------------------------------------------------------

  it("lists the Site's open threshold alerts", async () => {
    const routes: Routes = {
      ...AUTH,
      "GET /dashboard/live?site_id=s1": () => json(200, snapshot()),
      "GET /sites/s1/alerts?state=open": () => json(200, { items: [alert()] }),
    };
    stubApi(routes);

    renderApp(<DashboardPage />);

    expect(await screen.findByText("Queue length: 12 past a limit of 10 (1 breaches)")).toBeInTheDocument();
  });

  it("acknowledges an alert with a note (FR-MON-022)", async () => {
    const calls: Recorded[] = stubApi({
      ...AUTH,
      "GET /dashboard/live?site_id=s1": () => json(200, snapshot()),
      "GET /sites/s1/alerts?state=open": () => json(200, { items: [alert()] }),
      "POST /alerts/al1/acknowledge": () => json(200, alert({ state: "acknowledged", acknowledgement_note: "Opening a second counter" })),
    });
    const user = userEvent.setup();

    renderApp(<DashboardPage />);
    await screen.findByText("Queue length: 12 past a limit of 10 (1 breaches)");

    await user.type(screen.getByLabelText("Note"), "Opening a second counter");
    await user.click(screen.getByRole("button", { name: "Acknowledge" }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/alerts/al1/acknowledge")).toBe(true));
    const sent = calls.find((c) => c.method === "POST" && c.path === "/alerts/al1/acknowledge");
    expect(JSON.parse(String(sent?.init.body))).toEqual({ note: "Opening a second counter" });
  });

  it("flags an escalated alert", async () => {
    stubApi({
      ...AUTH,
      "GET /dashboard/live?site_id=s1": () => json(200, snapshot()),
      "GET /sites/s1/alerts?state=open": () => json(200, { items: [alert({ escalated_at: "2026-09-19T10:10:00Z" })] }),
    });

    renderApp(<DashboardPage />);

    expect(await screen.findByText("Escalated")).toBeInTheDocument();
  });
});
