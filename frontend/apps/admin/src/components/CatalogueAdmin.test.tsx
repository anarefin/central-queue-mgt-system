import type { CounterLink, CounterOption, OutcomeCode, ServiceEntry, ServiceGroup, Site, Team } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { CatalogueAdmin } from "./CatalogueAdmin";

const STAMP = "2026-09-19T20:30:00Z";
const SITE: Site = {
  id: "s1",
  name: "Main campus",
  code: "MAIN",
  timezone: "Asia/Dhaka",
  address: "1 Campus Road",
  default_language: "bn",
  enabled_languages: ["bn", "en"],
  active: true,
  created_at: STAMP,
  updated_at: STAMP,
};
const GROUP: ServiceGroup = {
  id: "g1",
  site_id: "s1",
  name_i18n: { bn: "বহির্বিভাগ", en: "Outpatient" },
  missing_translations: [],
  token_prefix: "OPD",
  display_order: 2,
  active: true,
  created_at: STAMP,
  updated_at: STAMP,
};
const SERVICE: ServiceEntry = {
  id: "v1",
  service_group_id: "g1",
  site_id: "s1",
  name_i18n: { bn: "পরামর্শ", en: "Consultation" },
  missing_translations: [],
  token_prefix: "CON",
  expected_minutes: 12,
  sla_wait_minutes: 30,
  channels: ["kiosk", "reception"],
  icon: "stethoscope",
  display_order: 3,
  visitor_identifier: "mandatory",
  booking_mode: "walk_in_only",
  active: true,
  created_at: STAMP,
  updated_at: STAMP,
};
const OPTIONS: CounterOption[] = [
  { id: "c1", zone_id: "z1", zone_name: "Ground waiting", label: "Counter 1" },
  { id: "c2", zone_id: "z1", zone_name: "Ground waiting", label: "Counter 2" },
];
const OUTCOME: OutcomeCode = {
  id: "o1",
  service_id: "v1",
  site_id: "s1",
  code: "resolved",
  label_i18n: { bn: "সমাধান", en: "Resolved" },
  missing_translations: [],
  display_order: 1,
  active: true,
  created_at: STAMP,
  updated_at: STAMP,
};

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

interface State {
  groups: ServiceGroup[];
  services: ServiceEntry[];
  links: CounterLink[];
  outcomes: OutcomeCode[];
  team: Team;
}

function freshState(over: Partial<State> = {}): State {
  return { groups: [], services: [], links: [], outcomes: [], team: { id: "t1", service_group_id: "g1", name: "Outpatient", members: [] }, ...over };
}

function bodyOf(call: Recorded | undefined): Record<string, unknown> {
  return JSON.parse(String(call?.init.body)) as Record<string, unknown>;
}

function missingOf(names: Record<string, string>): string[] {
  return SITE.enabled_languages.filter((language) => !names[language]);
}

/** An in-memory API, so a write is visible on the next list the screen fetches. */
function fakeApi(state: State, extra: Routes = {}) {
  const input = (init: RequestInit) => JSON.parse(String(init.body)) as Record<string, unknown>;
  const routes: Routes = {
    ...NO_SESSION,
    "GET /sites": () => json(200, { items: [SITE] }),
    "GET /sites/s1/service-groups": () => json(200, { items: state.groups }),
    "POST /sites/s1/service-groups": (init) => {
      const body = input(init) as { name_i18n: Record<string, string> };
      const names = Object.fromEntries(Object.entries(body.name_i18n).filter(([, text]) => text.trim() !== ""));
      const created = { ...GROUP, id: `g${state.groups.length + 1}`, ...body, name_i18n: names, missing_translations: missingOf(names) } as ServiceGroup;
      state.groups.push(created);
      return json(201, created);
    },
    "PATCH /service-groups/g1": (init) => {
      state.groups[0] = { ...state.groups[0]!, ...input(init) } as ServiceGroup;
      return json(200, state.groups[0]);
    },
    "POST /service-groups/g1/deactivate": () => {
      state.groups[0] = { ...state.groups[0]!, active: false };
      state.services = state.services.map((s) => ({ ...s, active: false }));
      return json(200, state.groups[0]);
    },
    "GET /service-groups/g1/services": () => json(200, { items: state.services }),
    "POST /service-groups/g1/services": (init) => {
      const created = { ...SERVICE, id: `v${state.services.length + 1}`, missing_translations: [], ...input(init) } as ServiceEntry;
      state.services.push(created);
      return json(201, created);
    },
    "POST /services/v1/deactivate": () => {
      state.services[0] = { ...state.services[0]!, active: false };
      return json(200, state.services[0]);
    },
    "DELETE /services/v1": () => {
      state.services = [];
      return new Response(null, { status: 204 });
    },
    "GET /service-groups/g1/counters": () => json(200, { items: OPTIONS }),
    "GET /services/v1/counters": () => json(200, { items: state.links }),
    "PUT /services/v1/counters/c1": (init) => {
      const weight = (init.body ? (input(init).preference_weight as number) : 1) ?? 1;
      state.links = [...state.links.filter((l) => l.counter_id !== "c1"), { counter_id: "c1", service_id: "v1", preference_weight: weight, counter_label: "Counter 1", counter_active: true }];
      return json(200, state.links.at(-1));
    },
    "PUT /services/v1/counters/c2": (init) => {
      const weight = (input(init).preference_weight as number) ?? 1;
      state.links = [...state.links.filter((l) => l.counter_id !== "c2"), { counter_id: "c2", service_id: "v1", preference_weight: weight, counter_label: "Counter 2", counter_active: true }];
      return json(200, state.links.at(-1));
    },
    "DELETE /services/v1/counters/c2": () => {
      state.links = state.links.filter((l) => l.counter_id !== "c2");
      return new Response(null, { status: 204 });
    },
    "GET /services/v1/outcome-codes": () => json(200, { items: state.outcomes }),
    "POST /services/v1/outcome-codes": (init) => {
      const body = input(init) as { code: string; label_i18n: Record<string, string> };
      const labels = Object.fromEntries(Object.entries(body.label_i18n).filter(([, text]) => text.trim() !== ""));
      const created = { ...OUTCOME, id: `o${state.outcomes.length + 1}`, ...body, label_i18n: labels, missing_translations: missingOf(labels) } as OutcomeCode;
      state.outcomes.push(created);
      return json(201, created);
    },
    "POST /outcome-codes/o1/deactivate": () => {
      state.outcomes[0] = { ...state.outcomes[0]!, active: false };
      return json(200, state.outcomes[0]);
    },
    "GET /service-groups/g1/team": () => json(200, state.team),
    "GET /users?limit=200": () =>
      json(200, {
        items: [
          { id: "u1", username: "asha", display_name: "Asha Rahman", active: true },
          { id: "u2", username: "bilal", display_name: null, active: true },
          { id: "u3", username: "gone", display_name: "Gone Person", active: false },
        ],
        next_cursor: null,
      }),
    "POST /service-groups/g1/team/members": (init) => {
      const userId = input(init).user_id as string;
      state.team = { ...state.team, members: [...state.team.members, { user_id: userId, username: "asha", display_name: "Asha Rahman", active: true, added_at: STAMP }] };
      return json(200, state.team);
    },
    "DELETE /service-groups/g1/team/members/u1": () => {
      state.team = { ...state.team, members: [] };
      return json(200, state.team);
    },
    ...extra,
  };
  return stubApi(routes);
}

async function openServices() {
  await userEvent.click(await screen.findByRole("button", { name: "Services Outpatient" }));
  return (await screen.findByText("Services of Outpatient")).closest("section")!;
}

describe("service catalogue screen", () => {
  it("lists a site's service groups by name in the reader's language with prefix, order and missing-translation warnings", async () => {
    fakeApi(freshState({ groups: [GROUP, { ...GROUP, id: "g2", name_i18n: { bn: "জরুরি" }, missing_translations: ["en"], token_prefix: "ER", display_order: 1 }] }));
    renderApp(<CatalogueAdmin />);

    expect(await screen.findByText("Outpatient")).toBeInTheDocument();
    expect(screen.getByText("Service groups of Main campus")).toBeInTheDocument();
    expect(screen.getByText("Token prefix: OPD · Display order: 2")).toBeInTheDocument();
    // English has no name for the second group: the site's default language stands in, and the row says so (FR-I18N-011).
    expect(screen.getByText("জরুরি")).toBeInTheDocument();
    expect(screen.getByText("Missing translation: English")).toBeInTheDocument();
  });

  it("shows one name input per enabled language, warns on a blank translation and still saves (FR-I18N-010)", async () => {
    const state = freshState();
    const calls = fakeApi(state);
    renderApp(<CatalogueAdmin />);
    await screen.findByText("This site has no service groups yet.");

    const card = screen.getByText("Service groups of Main campus").closest("section")!;
    const bangla = within(card).getByLabelText("Name (Bangla)");
    within(card).getByLabelText("Name (English)");
    expect(within(card).getAllByLabelText(/^Name \(/)).toHaveLength(2);
    expect(within(card).getByRole("status")).toHaveTextContent("No text in Bangla, English yet.");

    await userEvent.type(bangla, "বহির্বিভাগ");
    expect(within(card).getByRole("status")).toHaveTextContent("No text in English yet. You can still save; Bangla is shown until you add one.");
    await userEvent.type(within(card).getByLabelText("Token prefix"), "OPD");
    await userEvent.click(within(card).getByRole("button", { name: "Add a service group" }));

    // Saved, not blocked; the list now warns about the missing English name.
    expect(await screen.findByText("Missing translation: English")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/sites/s1/service-groups"))).toMatchObject({
      name_i18n: { bn: "বহির্বিভাগ", en: "" },
      token_prefix: "OPD",
    });
  });

  it("adds a service with prefix, times, channels, icon, order, visitor identifier and booking mode (FR-CFG-010, 012, 013, 014)", async () => {
    const state = freshState({ groups: [GROUP] });
    const calls = fakeApi(state);
    renderApp(<CatalogueAdmin />);
    const card = await openServices();
    await screen.findByText("This service group has no services yet.");

    await userEvent.type(within(card).getByLabelText("Name (Bangla)"), "পরামর্শ");
    await userEvent.type(within(card).getByLabelText("Name (English)"), "Consultation");
    await userEvent.type(within(card).getByLabelText("Token prefix"), "CON");
    await userEvent.type(within(card).getByLabelText("Expected handling time (minutes)"), "12");
    await userEvent.type(within(card).getByLabelText("SLA wait target (minutes)"), "30");
    await userEvent.click(within(card).getByLabelText("Mobile app"));
    await userEvent.click(within(card).getByLabelText("Appointment check-in"));
    await userEvent.type(within(card).getByLabelText("Kiosk icon (optional)"), "stethoscope");
    await userEvent.clear(within(card).getByLabelText("Display order"));
    await userEvent.type(within(card).getByLabelText("Display order"), "3");
    await userEvent.selectOptions(within(card).getByLabelText("Visitor identifier"), "mandatory");
    await userEvent.selectOptions(within(card).getByLabelText("Who can queue"), "walk_in_only");
    await userEvent.click(within(card).getByRole("button", { name: "Add a service" }));

    expect(await screen.findByText("Token prefix CON · Expected 12 min · SLA wait 30 min")).toBeInTheDocument();
    expect(screen.getByText("Channels: Kiosk, Reception · Visitor identifier: Mandatory · Walk-ins only")).toBeInTheDocument();
    expect(screen.getByText("Kiosk icon: stethoscope")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/service-groups/g1/services"))).toEqual({
      name_i18n: { bn: "পরামর্শ", en: "Consultation" },
      token_prefix: "CON",
      expected_minutes: 12,
      sla_wait_minutes: 30,
      channels: ["kiosk", "reception"],
      icon: "stethoscope",
      display_order: 3,
      visitor_identifier: "mandatory",
      booking_mode: "walk_in_only",
    });
  });

  it("links counters to a service with a preference weight and unlinks them (FR-CFG-011)", async () => {
    const state = freshState({
      groups: [GROUP],
      services: [SERVICE],
      links: [{ counter_id: "c1", service_id: "v1", preference_weight: 1, counter_label: "Counter 1", counter_active: true }],
    });
    const calls = fakeApi(state);
    renderApp(<CatalogueAdmin />);
    await openServices();
    await userEvent.click(await screen.findByRole("button", { name: "Counters Consultation" }));
    const card = (await screen.findByText("Counters serving Consultation")).closest("section")!;
    expect(await within(card).findByText("Counter 1 · weight 1")).toBeInTheDocument();

    // Only counters not yet linked can be picked.
    const picker = within(card).getByLabelText("Counter");
    await within(picker).findByRole("option", { name: "Ground waiting · Counter 2" });
    expect(within(picker).queryByRole("option", { name: "Ground waiting · Counter 1" })).not.toBeInTheDocument();
    await userEvent.selectOptions(picker, "c2");
    await userEvent.clear(within(card).getByLabelText("Preference weight"));
    await userEvent.type(within(card).getByLabelText("Preference weight"), "2");
    await userEvent.click(within(card).getByRole("button", { name: "Link a counter" }));

    expect(await within(card).findByText("Counter 2 · weight 2")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "PUT" && c.path === "/services/v1/counters/c2"))).toEqual({ preference_weight: 2 });

    await userEvent.click(within(card).getByRole("button", { name: "Unlink Counter 2" }));
    await waitFor(() => expect(within(card).queryByText("Counter 2 · weight 2")).not.toBeInTheDocument());
    expect(calls.some((c) => c.method === "DELETE" && c.path === "/services/v1/counters/c2")).toBe(true);
  });

  it("adds outcome codes with a label per language and deactivates them, never deleting (FR-AGT-032, FR-AGT-033)", async () => {
    const state = freshState({ groups: [GROUP], services: [SERVICE], outcomes: [OUTCOME] });
    const calls = fakeApi(state);
    renderApp(<CatalogueAdmin />);
    await openServices();
    await userEvent.click(await screen.findByRole("button", { name: "Outcome codes Consultation" }));
    const card = (await screen.findByText("Outcome codes of Consultation")).closest("section")!;
    expect(await within(card).findByText("Code: resolved · Display order: 1")).toBeInTheDocument();

    await userEvent.type(within(card).getByLabelText("Code"), "referred");
    await userEvent.type(within(card).getByLabelText("Label (Bangla)"), "রেফার");
    await userEvent.click(within(card).getByRole("button", { name: "Add an outcome code" }));

    expect(await within(card).findByText("Code: referred · Display order: 0")).toBeInTheDocument();
    expect(within(card).getByText("Missing translation: English")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/services/v1/outcome-codes"))).toMatchObject({
      code: "referred",
      label_i18n: { bn: "রেফার", en: "" },
    });

    await userEvent.click(within(card).getByRole("button", { name: "Deactivate Resolved" }));
    await userEvent.click(within(within(card).getByRole("group", { name: "Confirm deactivation Resolved" })).getByRole("button", { name: "Confirm deactivation" }));
    expect(await within(card).findByRole("button", { name: "Activate Resolved" })).toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: /^Delete/ })).not.toBeInTheDocument();
  });

  it("adds and removes team members directly, offering only enabled users who are not yet members", async () => {
    const state = freshState({ groups: [GROUP] });
    const calls = fakeApi(state);
    renderApp(<CatalogueAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Team Outpatient" }));
    const card = (await screen.findByText("Team of Outpatient")).closest("section")!;
    expect(await within(card).findByText("This team has no members yet.")).toBeInTheDocument();
    expect(within(card).getByText(/A Team Admin's change is a request/)).toBeInTheDocument();

    const picker = within(card).getByLabelText("User");
    await within(card).findByRole("option", { name: "Asha Rahman" });
    expect(within(picker).getByRole("option", { name: "bilal" })).toBeInTheDocument();
    expect(within(picker).queryByRole("option", { name: "Gone Person" })).not.toBeInTheDocument();
    await userEvent.selectOptions(picker, "u1");
    await userEvent.click(within(card).getByRole("button", { name: "Add a member" }));

    expect(await within(card).findByRole("button", { name: "Remove Asha Rahman" })).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/service-groups/g1/team/members"))).toEqual({ user_id: "u1" });
    expect(within(card).queryByRole("option", { name: "Asha Rahman" })).not.toBeInTheDocument();

    await userEvent.click(within(card).getByRole("button", { name: "Remove Asha Rahman" }));
    expect(await within(card).findByText("This team has no members yet.")).toBeInTheDocument();
  });

  it("explains that a service with tickets cannot be deleted and can only be deactivated (FR-CFG-015)", async () => {
    const state = freshState({ groups: [GROUP], services: [SERVICE] });
    fakeApi(state, {
      "DELETE /services/v1": () => json(409, { error: { code: "conflict", message: "x", details: { reason: "service_has_tickets" }, trace_id: "t" } }),
    });
    renderApp(<CatalogueAdmin />);
    const card = await openServices();

    await userEvent.click(await within(card).findByRole("button", { name: "Delete Consultation" }));
    expect(within(card).getByText(/A service that has tickets cannot be deleted; deactivate it instead\./)).toBeInTheDocument();
    await userEvent.click(within(within(card).getByRole("group", { name: "Confirm delete Consultation" })).getByRole("button", { name: "Confirm delete" }));

    expect(await within(card).findByRole("alert")).toHaveTextContent("This service has tickets, so it cannot be deleted. Deactivate it instead.");
    expect(within(card).getByText("Consultation")).toBeInTheDocument();

    await userEvent.click(within(card).getByRole("button", { name: "Deactivate Consultation" }));
    await userEvent.click(within(within(card).getByRole("group", { name: "Confirm deactivation Consultation" })).getByRole("button", { name: "Confirm deactivation" }));
    expect(await within(card).findByRole("button", { name: "Activate Consultation" })).toBeInTheDocument();
  });

  it("deletes a service nobody has used once the admin confirms", async () => {
    const state = freshState({ groups: [GROUP], services: [SERVICE] });
    const calls = fakeApi(state);
    renderApp(<CatalogueAdmin />);
    const card = await openServices();

    await userEvent.click(await within(card).findByRole("button", { name: "Delete Consultation" }));
    await userEvent.click(within(within(card).getByRole("group", { name: "Confirm delete Consultation" })).getByRole("button", { name: "Confirm delete" }));

    expect(await within(card).findByText("This service group has no services yet.")).toBeInTheDocument();
    expect(calls.some((c) => c.method === "DELETE" && c.path === "/services/v1")).toBe(true);
  });

  it("renders every label in Bangla with names in the reader's language", async () => {
    fakeApi(freshState({ groups: [GROUP], services: [SERVICE] }));
    renderApp(<CatalogueAdmin />, ["bn-BD"]);

    expect(await screen.findByText("বহির্বিভাগ")).toBeInTheDocument();
    expect(screen.getByText("Main campus-এর সেবা গ্রুপ")).toBeInTheDocument();
    expect(screen.getByLabelText("নাম (বাংলা)")).toBeInTheDocument();
    expect(screen.getByLabelText("নাম (ইংরেজি)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "সেবা বহির্বিভাগ" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "সেবা বহির্বিভাগ" }));
    expect(await screen.findByText("বহির্বিভাগ-এর সেবা")).toBeInTheDocument();
    expect(screen.getByText(/চ্যানেল: কিয়স্ক, অভ্যর্থনা · দর্শনার্থীর শনাক্তকারী: বাধ্যতামূলক · শুধু ওয়াক-ইন/)).toBeInTheDocument();
    await waitFor(() => expect(document.documentElement.lang).toBe("bn"));
  });
});
