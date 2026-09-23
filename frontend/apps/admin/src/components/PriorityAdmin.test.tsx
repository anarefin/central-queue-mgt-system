import type { PriorityClass, PriorityClassInput, PriorityDefaults, QueueDryRun, QueueStrategy, ServiceGroup, Site } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import Home from "../app/page";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { PriorityAdmin } from "./PriorityAdmin";

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
const SERVICES = {
  site_id: "s1",
  default_language: "en",
  items: [
    {
      id: "v1",
      name_i18n: { en: "Consultation", bn: "পরামর্শ" },
      service_group: { id: "g1", name_i18n: GROUP.name_i18n },
      token_prefix: "CON",
      icon: null,
      display_order: 1,
      waiting_count: 2,
      estimated_wait_minutes: null,
    },
  ],
};

function cls(over: Partial<PriorityClass>): PriorityClass {
  return {
    id: "c0",
    name_i18n: { en: "Normal", bn: "সাধারণ" },
    headstart_minutes: 0,
    max_wait_minutes: null,
    token_prefix_override: null,
    is_default: false,
    active: true,
    created_at: STAMP,
    updated_at: STAMP,
    ...over,
  };
}

const NORMAL = cls({ is_default: true, max_wait_minutes: 60 });
const SENIOR = cls({ id: "c1", name_i18n: { en: "Senior citizen", bn: "বয়স্ক নাগরিক" }, headstart_minutes: 20, max_wait_minutes: 45, token_prefix_override: "SC" });

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

interface State {
  classes: PriorityClass[];
  strategy: { strategy: QueueStrategy; is_default: boolean };
  defaults: PriorityDefaults;
}

const AVAILABLE: QueueStrategy[] = ["weighted_wait", "strict_priority", "fifo"];

/** An in-memory API, so a write is visible on the next read the screen makes. */
function fakeApi(state: State, extra: Routes = {}): Recorded[] {
  return stubApi({
    ...NO_SESSION,
    "GET /sites": () => json(200, { items: [SITE] }),
    "GET /sites/s1/service-groups": () => json(200, { items: [GROUP] }),
    "GET /sites/s1/services": () => json(200, SERVICES),
    "GET /priority-classes": () => json(200, { items: state.classes }),
    "POST /priority-classes": (init) => {
      const input = JSON.parse(String(init.body)) as PriorityClassInput;
      const created = cls({ id: "c9", ...input, is_default: false, active: true } as Partial<PriorityClass>);
      state.classes = [...state.classes, created];
      return json(201, created);
    },
    "PUT /priority-classes/c1": (init) => {
      const input = JSON.parse(String(init.body)) as PriorityClassInput;
      const updated = { ...SENIOR, ...input } as PriorityClass;
      state.classes = state.classes.map((c) => (c.id === "c1" ? updated : c));
      return json(200, updated);
    },
    "PUT /priority-classes/c0": (init) => {
      const input = JSON.parse(String(init.body)) as PriorityClassInput;
      const updated = { ...NORMAL, ...input } as PriorityClass;
      state.classes = state.classes.map((c) => (c.id === "c0" ? updated : c));
      return json(200, updated);
    },
    "POST /priority-classes/c1/deactivate": () => {
      state.classes = state.classes.map((c) => (c.id === "c1" ? { ...c, active: false } : c));
      return json(200, state.classes.find((c) => c.id === "c1"));
    },
    "POST /priority-classes/c1/activate": () => {
      state.classes = state.classes.map((c) => (c.id === "c1" ? { ...c, active: true } : c));
      return json(200, state.classes.find((c) => c.id === "c1"));
    },
    "GET /priority-defaults": () => json(200, state.defaults),
    "PUT /priority-defaults/channels/kiosk": (init) => {
      const saved = { channel: "kiosk" as const, priority_class_id: (JSON.parse(String(init.body)) as { priority_class_id: string | null }).priority_class_id };
      state.defaults = { ...state.defaults, channels: state.defaults.channels.map((c) => (c.channel === "kiosk" ? saved : c)) };
      return json(200, saved);
    },
    "PUT /priority-defaults/services/v1": (init) => {
      const classId = (JSON.parse(String(init.body)) as { priority_class_id: string | null }).priority_class_id;
      state.defaults = { ...state.defaults, services: classId ? [{ service_id: "v1", priority_class_id: classId }] : [] };
      return json(200, { service_id: "v1", priority_class_id: classId });
    },
    "GET /service-groups/g1/routing-strategy": () => json(200, { service_group_id: "g1", ...state.strategy, available: AVAILABLE }),
    "PUT /service-groups/g1/routing-strategy": (init) => {
      state.strategy = { strategy: (JSON.parse(String(init.body)) as { strategy: QueueStrategy }).strategy, is_default: false };
      return json(200, { service_group_id: "g1", ...state.strategy, available: AVAILABLE });
    },
    ...extra,
  });
}

const fresh = (): State => ({
  classes: [NORMAL, SENIOR],
  strategy: { strategy: "weighted_wait", is_default: true },
  defaults: {
    channels: [
      { channel: "kiosk", priority_class_id: null },
      { channel: "reception", priority_class_id: "c1" },
      { channel: "mobile", priority_class_id: null },
      { channel: "appointment_checkin", priority_class_id: null },
    ],
    services: [],
  },
});

function bodyOf(call: Recorded | undefined): unknown {
  return JSON.parse(String(call?.init.body));
}

function dryRun(over: Partial<QueueDryRun> = {}): QueueDryRun {
  return {
    service: { id: "v1", name_i18n: SERVICES.items[0]!.name_i18n },
    site_id: "s1",
    strategy: "weighted_wait",
    computed_at: "2026-09-19T10:00:00Z",
    waiting_count: 3,
    tickets: [
      {
        id: "t1",
        token_number: "CON-001",
        state: "waiting",
        position: 1,
        priority_class: { id: "c0", name_i18n: { en: "Normal", bn: "সাধারণ" } },
        max_wait_minutes: 60,
        queued_at: "2026-09-19T08:55:00Z",
        terms: { effective_wait_minutes: 65, headstart_minutes: 0, appointment_bonus: 0, escalation_bonus: 1000005, score_adjustment_minutes: 0, adjustment_overridden: true },
        score: 1000070,
        escalated: true,
      },
      {
        id: "t2",
        token_number: "CON-004",
        state: "waiting",
        position: 2,
        priority_class: { id: "c1", name_i18n: { en: "Senior citizen", bn: "বয়স্ক নাগরিক" } },
        max_wait_minutes: 45,
        queued_at: "2026-09-19T09:55:00Z",
        terms: { effective_wait_minutes: 5, headstart_minutes: 20, appointment_bonus: 0, escalation_bonus: 0, score_adjustment_minutes: -3, adjustment_overridden: false },
        score: 22,
        escalated: false,
      },
      {
        id: "t3",
        token_number: "CON-003",
        state: "waiting",
        position: 3,
        priority_class: null,
        max_wait_minutes: null,
        queued_at: "2026-09-19T09:50:00Z",
        terms: { effective_wait_minutes: 10, headstart_minutes: 0, appointment_bonus: 0, escalation_bonus: 0, score_adjustment_minutes: 0, adjustment_overridden: false },
        score: 10,
        escalated: false,
      },
    ],
    ...over,
  };
}

describe("priority classes (FR-QUE-010)", () => {
  it("lists the default class first and each class's head start, maximum wait and prefix in words", async () => {
    fakeApi(fresh());
    renderApp(<PriorityAdmin />);

    const items = await screen.findAllByRole("listitem");
    const rows = items.filter((li) => li.textContent?.includes("Head start:"));
    expect(rows[0]).toHaveTextContent("Normal");
    expect(rows[0]).toHaveTextContent("Head start: 0 min · Maximum wait: 60 min · Prefix: none");
    expect(rows[0]).toHaveTextContent("The default class always has a head start of 0");
    expect(rows[1]).toHaveTextContent("Senior citizen");
    expect(rows[1]).toHaveTextContent("Head start: 20 min · Maximum wait: 45 min · Prefix: SC");
  });

  it("does not offer to deactivate the default class", async () => {
    fakeApi(fresh());
    renderApp(<PriorityAdmin />);

    await screen.findByRole("button", { name: "Deactivate Senior citizen" });
    expect(screen.queryByRole("button", { name: "Deactivate Normal" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit Normal" })).toBeInTheDocument();
  });

  it("creates a class with names, head start, maximum wait and prefix override, and lists it", async () => {
    const calls = fakeApi(fresh());
    renderApp(<PriorityAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Add priority class" }));

    await userEvent.type(screen.getByLabelText("Name (English)"), "Emergency");
    await userEvent.type(screen.getByLabelText("Name (Bangla)"), "জরুরি");
    await userEvent.clear(screen.getByLabelText("Head start (minutes)"));
    await userEvent.type(screen.getByLabelText("Head start (minutes)"), "90");
    await userEvent.type(screen.getByLabelText("Maximum wait (minutes, optional)"), "15");
    await userEvent.type(screen.getByLabelText("Token prefix override (optional)"), "EM");
    await userEvent.click(screen.getByRole("button", { name: "Create priority class" }));

    expect(await screen.findByText("Head start: 90 min · Maximum wait: 15 min · Prefix: EM")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/priority-classes"))).toEqual({
      name_i18n: { en: "Emergency", bn: "জরুরি" },
      headstart_minutes: 90,
      max_wait_minutes: 15,
      token_prefix_override: "EM",
    });
  });

  it("sends no maximum wait and no prefix when they are left blank, and names the fields the API refuses", async () => {
    const calls = fakeApi(fresh(), {
      "POST /priority-classes": () =>
        json(400, { error: { code: "validation_failed", message: "x", trace_id: "t", details: { fields: [{ field: "headstart_minutes", code: "Range" }] } } }),
    });
    renderApp(<PriorityAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Add priority class" }));
    await userEvent.type(screen.getByLabelText("Name (English)"), "Nope");
    await userEvent.clear(screen.getByLabelText("Head start (minutes)"));
    await userEvent.type(screen.getByLabelText("Head start (minutes)"), "5000");
    await userEvent.click(screen.getByRole("button", { name: "Create priority class" }));

    expect(await screen.findByText(/The request contains invalid data\. Check these fields: Head start \(minutes\)/)).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/priority-classes"))).toMatchObject({ max_wait_minutes: null, token_prefix_override: null });
  });

  it("edits a class from its current values and replaces it as a whole", async () => {
    const calls = fakeApi(fresh());
    renderApp(<PriorityAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Edit Senior citizen" }));

    expect(screen.getByLabelText("Head start (minutes)")).toHaveValue(20);
    expect(screen.getByLabelText("Maximum wait (minutes, optional)")).toHaveValue(45);
    expect(screen.getByLabelText("Token prefix override (optional)")).toHaveValue("SC");
    await userEvent.clear(screen.getByLabelText("Head start (minutes)"));
    await userEvent.type(screen.getByLabelText("Head start (minutes)"), "30");
    await userEvent.clear(screen.getByLabelText("Maximum wait (minutes, optional)"));
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText("Head start: 30 min · Maximum wait: none · Prefix: SC")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "PUT" && c.path === "/priority-classes/c1"))).toEqual({
      name_i18n: { en: "Senior citizen", bn: "বয়স্ক নাগরিক" },
      headstart_minutes: 30,
      max_wait_minutes: null,
      token_prefix_override: "SC",
    });
  });

  it("lets the default class take a maximum wait but never a head start or a prefix", async () => {
    const calls = fakeApi(fresh());
    renderApp(<PriorityAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Edit Normal" }));

    expect(screen.queryByLabelText("Head start (minutes)")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Token prefix override (optional)")).not.toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText("Maximum wait (minutes, optional)"));
    await userEvent.type(screen.getByLabelText("Maximum wait (minutes, optional)"), "90");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(bodyOf(calls.find((c) => c.method === "PUT" && c.path === "/priority-classes/c0"))).toMatchObject({ headstart_minutes: 0, max_wait_minutes: 90, token_prefix_override: null }));
    expect(await screen.findByText("Head start: 0 min · Maximum wait: 90 min · Prefix: none")).toBeInTheDocument();
  });

  it("deactivates after confirmation, saying tickets keep the class, and activates again", async () => {
    const calls = fakeApi(fresh());
    renderApp(<PriorityAdmin />);

    await userEvent.click(await screen.findByRole("button", { name: "Deactivate Senior citizen" }));
    expect(screen.getByText(/Senior citizen will no longer be offered when a token is issued\. Tokens that already have it keep it\./)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Confirm deactivation" }));

    const activate = await screen.findByRole("button", { name: "Activate Senior citizen" });
    expect(calls.some((c) => c.method === "POST" && c.path === "/priority-classes/c1/deactivate")).toBe(true);
    await userEvent.click(activate);
    await screen.findByRole("button", { name: "Deactivate Senior citizen" });
    expect(calls.some((c) => c.method === "POST" && c.path === "/priority-classes/c1/activate")).toBe(true);
  });
});

describe("ordering strategy per service group (FR-QUE-021)", () => {
  it("says a group that has not chosen uses weighted wait, then saves the strategy picked", async () => {
    const state = fresh();
    const calls = fakeApi(state);
    renderApp(<PriorityAdmin />);

    expect(await screen.findByText("Not chosen yet: weighted wait is used.")).toBeInTheDocument();
    const select = screen.getByLabelText("Strategy of Outpatient");
    expect(within(select).getAllByRole("option").map((o) => o.textContent)).toEqual([
      "Weighted wait: wait + head start + bonuses",
      "Strict priority: class first, then first come first served",
      "First come first served: creation order only",
    ]);
    await userEvent.selectOptions(select, "strict_priority");
    await userEvent.click(screen.getByRole("button", { name: "Save strategy Outpatient" }));

    expect(await screen.findByText(/Saved\. The queue follows this strategy from its next read/)).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "PUT" && c.path === "/service-groups/g1/routing-strategy"))).toEqual({ strategy: "strict_priority" });
    expect(screen.queryByText("Not chosen yet: weighted wait is used.")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Strategy of Outpatient")).toHaveValue("strict_priority");
  });

  it("shows the strategy a group already has and a refusal from the API", async () => {
    const state = fresh();
    state.strategy = { strategy: "fifo", is_default: false };
    fakeApi(state, { "PUT /service-groups/g1/routing-strategy": () => json(403, { error: { code: "forbidden", message: "x", trace_id: "t" } }) });
    renderApp(<PriorityAdmin />);

    await waitFor(() => expect(screen.getByLabelText("Strategy of Outpatient")).toHaveValue("fifo"));
    await userEvent.selectOptions(screen.getByLabelText("Strategy of Outpatient"), "weighted_wait");
    await userEvent.click(screen.getByRole("button", { name: "Save strategy Outpatient" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("You do not have permission to do this.");
    expect(screen.queryByText(/Saved\. The queue follows/)).not.toBeInTheDocument();
  });
});

describe("dry run (FR-QUE-023)", () => {
  it("shows each waiting ticket's place and every term of its score, and flags the escalated ones", async () => {
    const calls = fakeApi(fresh(), { "GET /queues/v1/dry-run": () => json(200, dryRun()) });
    renderApp(<PriorityAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Run dry run" }));

    const summary = await screen.findByText(/3 waiting, ordered by Weighted wait: wait \+ head start \+ bonuses at/);
    expect(summary).toBeInTheDocument();
    const table = screen.getByRole("table");
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Place",
      "Token",
      "Class",
      "Waited (min)",
      "Head start",
      "Appointment bonus",
      "Escalation bonus",
      "Score adjustment",
      "Score",
      "Status",
    ]);
    const [, first, second, third] = within(table).getAllByRole("row");
    expect(within(first!).getAllByRole("cell").map((c) => c.textContent)).toEqual([
      "1",
      "Normal",
      "65",
      "0",
      "0",
      "1,000,005",
      "0",
      "1,000,070",
      "Escalated Adjustment set aside by escalation",
    ]);
    expect(within(first!).getByRole("rowheader")).toHaveTextContent("CON-001");
    expect(within(second!).getAllByRole("cell").map((c) => c.textContent)).toEqual(["2", "Senior citizen", "5", "20", "0", "0", "-3", "22", ""]);
    expect(within(third!).getAllByRole("cell")[1]).toHaveTextContent("—");
    expect(calls.find((c) => c.path.startsWith("/queues/v1/dry-run"))?.path).toBe("/queues/v1/dry-run");
    expect(calls.filter((c) => c.method !== "GET" && c.path !== "/auth/refresh")).toEqual([]);
  });

  it("tries another strategy without saving it", async () => {
    const calls = fakeApi(fresh(), {
      "GET /queues/v1/dry-run?strategy=fifo": () => json(200, dryRun({ strategy: "fifo" })),
    });
    renderApp(<PriorityAdmin />);
    await userEvent.selectOptions(await screen.findByLabelText("Strategy to try"), "fifo");
    await userEvent.click(screen.getByRole("button", { name: "Run dry run" }));

    expect(await screen.findByText(/3 waiting, ordered by First come first served: creation order only at/)).toBeInTheDocument();
    expect(calls.some((c) => c.path === "/queues/v1/dry-run?strategy=fifo")).toBe(true);
    expect(calls.some((c) => c.method === "PUT")).toBe(false);
  });

  it("says nobody is waiting, and shows a refusal from the API", async () => {
    let allowed = true;
    fakeApi(fresh(), {
      "GET /queues/v1/dry-run": () =>
        allowed ? json(200, dryRun({ waiting_count: 0, tickets: [] })) : json(403, { error: { code: "forbidden", message: "x", trace_id: "t" } }),
    });
    renderApp(<PriorityAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Run dry run" }));
    expect(await screen.findByText("Nobody is waiting for this service.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();

    allowed = false;
    await userEvent.click(screen.getByRole("button", { name: "Run dry run" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("You do not have permission to do this.");
  });
});

describe("priority screen", () => {
  it("shows Bangla labels", async () => {
    fakeApi(fresh());
    renderApp(<PriorityAdmin />, ["bn-BD"]);

    expect(await screen.findByText("অগ্রাধিকার শ্রেণি")).toBeInTheDocument();
    expect(await screen.findByText("বয়স্ক নাগরিক")).toBeInTheDocument();
    expect(screen.getByText("হেড স্টার্ট: 20 মিনিট · সর্বোচ্চ অপেক্ষা: 45 মিনিট · প্রিফিক্স: SC")).toBeInTheDocument();
    expect(await screen.findByText("ড্রাই রান")).toBeInTheDocument();
  });

  it("links to the screen from the home screen for an administrator only; the API enforces access either way", async () => {
    const home = (roles: string[]): Routes => ({
      "POST /auth/refresh": () => json(200, { access_token: "tok", token_type: "Bearer", expires_in: 900 }),
      "GET /auth/me": () => json(200, { id: "u1", username: "asha", display_name: "Asha", preferred_language: null, roles, sites: ["s1"], groups: [] }),
      "GET /health/dependencies": () => json(200, { status: "up", dependencies: {} }),
    });
    stubApi(home(["org_admin"]));
    const admin = renderApp(<Home />);
    const link = await screen.findByRole("link", { name: "Priority and queue ordering" });
    expect(link.getAttribute("href")).toMatch(/^\/priority\/?$/);
    admin.unmount();

    stubApi(home(["reception_operator"]));
    renderApp(<Home />);
    await screen.findByRole("link", { name: "Reception desk" });
    expect(screen.queryByRole("link", { name: "Priority and queue ordering" })).not.toBeInTheDocument();
  });
});

describe("default classes (FR-QUE-011, FR-CFG-041)", () => {
  it("shows the default of each channel and service and says a default never changes a ticket that is already waiting", async () => {
    fakeApi(fresh());
    renderApp(<PriorityAdmin />);

    const card = (await screen.findByRole("heading", { name: "Default classes" })).closest("section")!;
    expect(within(card).getByText(/tokens already waiting keep the class they have/)).toBeInTheDocument();
    expect(await within(card).findByLabelText("Default class for Reception")).toHaveValue("c1");
    expect(within(card).getByLabelText("Default class for Kiosk")).toHaveValue("");
    expect(within(card).getByLabelText("Default class for Consultation")).toHaveValue("");
    const options = within(within(card).getByLabelText("Default class for Kiosk")).getAllByRole("option").map((o) => o.textContent);
    expect(options).toEqual(["No default (normal class)", "Senior citizen"]);
  });

  it("saves a channel default and a service default, and clears one with the no-default choice", async () => {
    const calls = fakeApi(fresh());
    renderApp(<PriorityAdmin />);
    const card = (await screen.findByRole("heading", { name: "Default classes" })).closest("section")!;

    const kiosk = await within(card).findByLabelText("Default class for Kiosk");
    expect(within(card).getByRole("button", { name: "Save default Kiosk" })).toBeDisabled();
    await userEvent.selectOptions(kiosk, "Senior citizen");
    await userEvent.click(within(card).getByRole("button", { name: "Save default Kiosk" }));
    expect((await within(card).findAllByText(/Tokens already issued keep the class they have/))[0]).toBeInTheDocument();
    const put = calls.filter((c) => c.method === "PUT" && c.path.startsWith("/priority-defaults"));
    expect(put[0]!.path).toBe("/priority-defaults/channels/kiosk");
    expect(bodyOf(put[0])).toEqual({ priority_class_id: "c1" });

    await userEvent.selectOptions(within(card).getByLabelText("Default class for Consultation"), "Senior citizen");
    await userEvent.click(within(card).getByRole("button", { name: "Save default Consultation" }));
    await waitFor(() => expect(calls.filter((c) => c.path === "/priority-defaults/services/v1")).toHaveLength(1));
    expect(bodyOf(calls.find((c) => c.path === "/priority-defaults/services/v1"))).toEqual({ priority_class_id: "c1" });

    await userEvent.selectOptions(within(card).getByLabelText("Default class for Consultation"), "No default (normal class)");
    await userEvent.click(within(card).getByRole("button", { name: "Save default Consultation" }));
    await waitFor(() => expect(calls.filter((c) => c.path === "/priority-defaults/services/v1")).toHaveLength(2));
    expect(bodyOf(calls.filter((c) => c.path === "/priority-defaults/services/v1")[1])).toEqual({ priority_class_id: null });
  });

  it("says in words why the API refused a default", async () => {
    fakeApi(fresh(), {
      "PUT /priority-defaults/channels/kiosk": () => json(403, { error: { code: "forbidden", message: "x", trace_id: "t" } }),
    });
    renderApp(<PriorityAdmin />);
    const card = (await screen.findByRole("heading", { name: "Default classes" })).closest("section")!;
    await userEvent.selectOptions(await within(card).findByLabelText("Default class for Kiosk"), "Senior citizen");
    await userEvent.click(within(card).getByRole("button", { name: "Save default Kiosk" }));

    expect(await within(card).findByRole("alert")).toHaveTextContent("You do not have permission");
  });

  it("is in Bangla too", async () => {
    fakeApi(fresh());
    renderApp(<PriorityAdmin />, ["bn-BD"]);

    const card = (await screen.findByRole("heading", { name: "ডিফল্ট শ্রেণি" })).closest("section")!;
    expect(await within(card).findByLabelText("অভ্যর্থনা-এর ডিফল্ট শ্রেণি")).toHaveValue("c1");
  });
});

