import type { Alert, DashboardSnapshot } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from "vitest";
import DashboardPage from "../app/dashboard/page";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";

const router = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }));
const searchParams = vi.hoisted(() => new URLSearchParams("site_id=s1"));
vi.mock("next/navigation", () => ({
  useRouter: () => router,
  useSearchParams: () => searchParams,
}));

const STAMP = "2026-09-19T09:55:00Z";
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

// ---- ticket 64: pickers instead of raw ids, the URL round trip, and the client-side permission gate -----------

describe("permission gate (ticket 64, SRS §5.2 dashboard:view_all / dashboard:view_own_groups)", () => {
  it("starts an org-wide admin (an empty sites claim) on the first site they can list, not on an empty site prompt", async () => {
    searchParams.delete("site_id"); // opened from the menu: no site in the URL
    onTestFinished(() => searchParams.set("site_id", "s1"));
    stubApi({
      ...AUTH,
      "GET /auth/me": () => json(200, { ...ME, sites: [] }),
      "GET /sites": () => json(200, { items: [{ id: "s1", code: "AARONG-CS", name: "Aarong Central Services" }] }),
      "GET /dashboard/live?site_id=s1": () => json(200, snapshot()),
    });

    renderApp(<DashboardPage />);

    expect(await screen.findByText("Open: 2")).toBeInTheDocument();
    expect(screen.queryByText("Choose a Site to see its live dashboard.")).not.toBeInTheDocument();
  });

  it("lists desks in reading order (2 before 10) and names officer statuses in words, not wire values", async () => {
    stubApi({
      ...AUTH,
      "GET /dashboard/live?site_id=s1": () =>
        json(
          200,
          snapshot({
            served_per_open_counter: [
              { counter_id: "c10", label: "10", session_id: null, served_count: 1 },
              { counter_id: "c2", label: "2", session_id: null, served_count: 3 },
            ],
          }),
        ),
    });

    renderApp(<DashboardPage />);

    const two = await screen.findByText("2: 3");
    const ten = screen.getByText("10: 1");
    expect(two.compareDocumentPosition(ten) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByRole("option", { name: "On break" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "on_break" })).not.toBeInTheDocument();
  });

  it("shows the dashboard to an agent, scoped to their own groups", async () => {
    stubApi({ ...AUTH, "GET /auth/me": () => json(200, { ...ME, roles: ["agent"] }), "GET /dashboard/live?site_id=s1": () => json(200, snapshot()) });

    renderApp(<DashboardPage />);

    expect(await screen.findByText("Open: 2")).toBeInTheDocument();
  });

  it("shows the dashboard to a team_admin", async () => {
    stubApi({ ...AUTH, "GET /auth/me": () => json(200, { ...ME, roles: ["team_admin"] }), "GET /dashboard/live?site_id=s1": () => json(200, snapshot()) });

    renderApp(<DashboardPage />);

    expect(await screen.findByText("Open: 2")).toBeInTheDocument();
  });

  it("tells a principal with neither dashboard permission instead of a broken page, with a link back to the counter", async () => {
    stubApi({ ...AUTH, "GET /auth/me": () => json(200, { ...ME, roles: ["visitor"] }) });

    renderApp(<DashboardPage />);

    expect(await screen.findByText("You do not have permission to view the dashboard")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to the counter" })).toHaveAttribute("href", "/");
    expect(screen.queryByTestId("live-dashboard")).not.toBeInTheDocument();
  });

  it("shows the same state when the server itself refuses the live snapshot with a 403", async () => {
    stubApi({ ...AUTH, "GET /dashboard/live?site_id=s1": () => json(403, { error: { code: "forbidden", message: "x", trace_id: "t" } }) });

    renderApp(<DashboardPage />);

    expect(await screen.findByText("You do not have permission to view the dashboard")).toBeInTheDocument();
  });
});

describe("filter pickers load on demand and round-trip through the URL (ticket 64, FR-MON-002)", () => {
  it("lists only the sites the caller belongs to, loaded once the site picker is opened, not before", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /dashboard/live?site_id=s1": () => json(200, snapshot()),
      "GET /sites": () =>
        json(200, {
          items: [
            { id: "s1", name: "Central", code: "C1", timezone: "Asia/Dhaka", address: "", default_language: "en", enabled_languages: ["en"], active: true },
            { id: "s2", name: "Other", code: "O1", timezone: "Asia/Dhaka", address: "", default_language: "en", enabled_languages: ["en"], active: true },
          ],
        }),
    });
    const user = userEvent.setup();
    renderApp(<DashboardPage />);
    await screen.findByText("Open: 2");
    expect(calls.some((c) => c.path === "/sites"), "not loaded before the picker is opened").toBe(false);

    const site = screen.getByRole("combobox", { name: "Site" });
    await user.click(site);

    await waitFor(() => expect(within(site).getAllByRole("option").map((o) => o.textContent)).toEqual(["Central"]));
  });

  it("loads a chosen site's zones once the zone picker is opened, and round-trips the choice through the URL", async () => {
    stubApi({
      ...AUTH,
      "GET /dashboard/live?site_id=s1": () => json(200, snapshot()),
      "GET /sites/s1/zones": () =>
        json(200, { items: [{ id: "z1", site_id: "s1", name: "Hall", building_label: null, floor_label: "1", display_order: 1, active: true, created_at: STAMP, updated_at: STAMP }] }),
    });
    const user = userEvent.setup();
    renderApp(<DashboardPage />);
    await screen.findByText("Open: 2");

    const zone = screen.getByRole("combobox", { name: "Zone" });
    await user.click(zone);
    await waitFor(() => expect(within(zone).getByRole("option", { name: "Hall" })).toBeInTheDocument());
    await user.selectOptions(zone, "Hall");

    expect(router.replace).toHaveBeenCalledWith("?site_id=s1&zone_id=z1");
  });

  it("loads the chosen site's service groups, and updates the URL when one is chosen", async () => {
    stubApi({
      ...AUTH,
      "GET /dashboard/live?site_id=s1": () => json(200, snapshot()),
      "GET /sites/s1/service-groups": () =>
        json(200, { items: [{ id: "g1", site_id: "s1", name_i18n: { en: "Outpatient" }, missing_translations: [], token_prefix: "A", display_order: 1, active: true, created_at: STAMP, updated_at: STAMP }] }),
    });
    const user = userEvent.setup();
    renderApp(<DashboardPage />);
    await screen.findByText("Open: 2");

    const service = screen.getByRole("combobox", { name: "Service" });
    expect(service).toBeDisabled();
    expect(screen.getByText("Choose a service group first.")).toBeInTheDocument();

    const group = screen.getByRole("combobox", { name: "Service group" });
    await user.click(group);
    await waitFor(() => expect(within(group).getByRole("option", { name: "Outpatient" })).toBeInTheDocument());
    await user.selectOptions(group, "Outpatient");

    expect(router.replace).toHaveBeenCalledWith("?site_id=s1&service_group_id=g1");
  });

  it("enables the service picker once the URL already names a service group, and loads its services", async () => {
    searchParams.set("service_group_id", "g1");
    try {
      stubApi({
        ...AUTH,
        "GET /dashboard/live?site_id=s1&service_group_id=g1": () => json(200, snapshot()),
        "GET /service-groups/g1/services": () =>
          json(200, {
            items: [
              {
                id: "v1",
                service_group_id: "g1",
                site_id: "s1",
                name_i18n: { en: "Consultation" },
                missing_translations: [],
                token_prefix: "A",
                expected_minutes: 5,
                sla_wait_minutes: 15,
                channels: ["reception"],
                icon: null,
                display_order: 1,
                visitor_identifier: "none",
              },
            ],
          }),
      });
      const user = userEvent.setup();
      renderApp(<DashboardPage />);
      await screen.findByText("Open: 2");

      const service = screen.getByRole("combobox", { name: "Service" });
      expect(service).toBeEnabled();
      await user.click(service);
      await waitFor(() => expect(within(service).getByRole("option", { name: "Consultation" })).toBeInTheDocument());
    } finally {
      searchParams.delete("service_group_id");
    }
  });

  it("re-prioritises a waiting ticket with a class chosen from a picker, staying disabled until both are chosen", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /dashboard/live?site_id=s1": () => json(200, snapshot()),
      "GET /priority-classes": () =>
        json(200, { items: [{ id: "p1", name_i18n: { en: "Senior citizen" }, headstart_minutes: 10, max_wait_minutes: null, token_prefix_override: null, is_default: false, active: true, created_at: STAMP, updated_at: STAMP }] }),
      "POST /tickets/t1/priority": () => json(200, { id: "t1" }),
    });
    const user = userEvent.setup();
    renderApp(<DashboardPage />);
    await screen.findByText("Open: 2");
    const card = within(screen.getByText("Longest waits").closest("section")!);

    const submit = card.getByRole("button", { name: "Change priority" });
    expect(submit).toBeDisabled();

    await user.selectOptions(card.getByRole("combobox", { name: "Re-prioritise" }), "A-001");
    expect(submit, "no class chosen yet").toBeDisabled();

    const classPicker = card.getByRole("combobox", { name: "Priority class" });
    await user.click(classPicker);
    await waitFor(() => expect(within(classPicker).getByRole("option", { name: "Senior citizen" })).toBeInTheDocument());
    await user.selectOptions(classPicker, "Senior citizen");
    expect(submit).toBeEnabled();

    await user.click(submit);

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/tickets/t1/priority")).toBe(true));
  });

  it("sets an agent's availability chosen from a picker, staying scoped to the current site", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /dashboard/live?site_id=s1": () => json(200, snapshot()),
      "GET /agents/availability": () => json(200, { items: [{ agent_id: "u2", agent_name: "Karim", status: "available", session_id: "sess2", counter: { id: "c2", label: "Desk 2", zone_id: "z1", zone_name: "Hall", site_id: "s1" }, break: null }] }),
      "PUT /agents/u2/availability": () => json(200, {}),
    });
    const user = userEvent.setup();
    renderApp(<DashboardPage />);
    await screen.findByText("Open: 2");

    const agent = screen.getByRole("combobox", { name: "Agent" });
    await user.click(agent);
    await waitFor(() => expect(within(agent).getByRole("option", { name: "Karim" })).toBeInTheDocument());
    await user.selectOptions(agent, "Karim");
    await user.click(screen.getByRole("button", { name: "Change agent status" }));

    const put = calls.find((c) => c.method === "PUT" && c.path === "/agents/u2/availability");
    expect(put).toBeDefined();
    expect(JSON.parse(String(put?.init.body))).toEqual({ status: "available" });
  });

  it("has no text input for a raw id: every id-shaped field is a picker or select, not a free-text box", async () => {
    stubApi({ ...AUTH, "GET /dashboard/live?site_id=s1": () => json(200, snapshot()) });
    renderApp(<DashboardPage />);
    await screen.findByText("Open: 2");

    for (const input of screen.queryAllByRole("textbox")) {
      expect(input.getAttribute("name") ?? "").not.toMatch(/_id$/);
      expect(input.getAttribute("id") ?? "").not.toMatch(/_id$/);
    }
  });
});
