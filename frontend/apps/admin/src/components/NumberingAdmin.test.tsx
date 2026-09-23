import type { NumberingRule, NumberingRuleInput, ServiceGroup, Site } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import Home from "../app/page";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { NumberingAdmin } from "./NumberingAdmin";

const router = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

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
  clinical_sensitivity: false,
  created_at: STAMP,
  updated_at: STAMP,
};
const GROUP: ServiceGroup = {
  id: "g1",
  site_id: "s1",
  name_i18n: { bn: "বহির্বিভাগ", en: "Outpatient" },
  missing_translations: [],
  token_prefix: "OPD",
  display_order: 1,
  active: true,
  created_at: STAMP,
  updated_at: STAMP,
};
const SERVICES = {
  site_id: "s1",
  default_language: "bn",
  items: [
    {
      id: "v1",
      name_i18n: { bn: "পরামর্শ", en: "Consultation" },
      service_group: { id: "g1", name_i18n: GROUP.name_i18n },
      token_prefix: "CON",
      icon: null,
      display_order: 1,
      waiting_count: 0,
      estimated_wait_minutes: null,
    },
    {
      id: "v2",
      name_i18n: { bn: "পরীক্ষা", en: "Check-up" },
      service_group: { id: "g1", name_i18n: GROUP.name_i18n },
      token_prefix: "CHK",
      icon: null,
      display_order: 2,
      waiting_count: 0,
      estimated_wait_minutes: null,
    },
  ],
};
const RULE: NumberingRule = {
  id: "r1",
  site_id: "s1",
  scope_type: "service_group",
  scope_id: "g1",
  prefix_source: "service_group",
  fixed_prefix: null,
  sequence_start: 1,
  padding: 3,
  reset_boundary: "daily",
  reset_time: "04:00",
  separator: "-",
  created_at: STAMP,
  updated_at: STAMP,
};

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function bodyOf(call: Recorded | undefined): NumberingRuleInput {
  return JSON.parse(String(call?.init.body)) as NumberingRuleInput;
}

/** An in-memory API, so a write is visible on the next list the screen fetches. */
function fakeApi(state: { rules: NumberingRule[]; waiting?: number }, extra: Routes = {}) {
  const routes: Routes = {
    ...NO_SESSION,
    "GET /sites": () => json(200, { items: [SITE] }),
    "GET /sites/s1/service-groups": () => json(200, { items: [GROUP] }),
    "GET /sites/s1/services": () => json(200, SERVICES),
    "GET /sites/s1/numbering-rules": () => json(200, { items: state.rules }),
    "PUT /service-groups/g1/numbering-rule": (init) => {
      const input = JSON.parse(String(init.body)) as NumberingRuleInput;
      const rule = { ...RULE, fixed_prefix: null, ...input } as NumberingRule;
      state.rules = state.rules.filter((r) => r.scope_id !== "g1").concat(rule);
      return json(200, { rule, affected_waiting_tickets: state.waiting ?? 0 });
    },
    "DELETE /service-groups/g1/numbering-rule": () => {
      state.rules = state.rules.filter((r) => r.scope_id !== "g1");
      return json(200, { rule: null, affected_waiting_tickets: state.waiting ?? 0 });
    },
    ...extra,
  };
  return stubApi(routes);
}

describe("token numbering screen", () => {
  it("shows each group's and service's rule in words, and where a service without a rule gets its numbers", async () => {
    fakeApi({
      rules: [
        RULE,
        { ...RULE, id: "r2", scope_type: "service", scope_id: "v2", prefix_source: "fixed", fixed_prefix: "VIP", separator: "", padding: 0, sequence_start: 10, reset_boundary: "never" },
      ],
    });
    renderApp(<NumberingAdmin />);

    expect(await screen.findByText("Token numbering of Main campus")).toBeInTheDocument();
    // The rule text depends on a second round of fetches (groups, services, rules) after the site itself loads, so
    // it can still be in flight the instant the heading above appears; await it rather than assuming it is already there.
    expect(await screen.findByText("Prefix: The service group's prefix · Separator: - · Digits: 3 · First number: 1 · Restarts every day at 04:00")).toBeInTheDocument();
    expect(screen.getByText("No rule of its own: follows its service group's rule.")).toBeInTheDocument();
    expect(screen.getByText("Prefix: fixed text VIP · Separator: (none) · Digits: 0 · First number: 10 · Never restarts")).toBeInTheDocument();
    expect(screen.getByText("Consultation")).toBeInTheDocument();
    expect(screen.getByText("Check-up")).toBeInTheDocument();
  });

  it("says the default applies where nothing is configured", async () => {
    fakeApi({ rules: [] });
    renderApp(<NumberingAdmin />);

    // The group and both of its services fall back to the default.
    expect(await screen.findAllByText(/^No rule: the default applies/)).toHaveLength(3);
    expect(screen.queryByText(/follows its service group's rule/)).not.toBeInTheDocument();
  });

  it("sets a rule with every parameter of FR-CFG-018 and warns that waiting tickets keep their numbers (FR-CFG-041)", async () => {
    const state = { rules: [] as NumberingRule[], waiting: 3 };
    const calls = fakeApi(state);
    renderApp(<NumberingAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Set rule Outpatient" }));

    await userEvent.selectOptions(screen.getByLabelText("Prefix comes from"), "fixed");
    await userEvent.type(screen.getByLabelText("Fixed prefix"), "VIP");
    await userEvent.clear(screen.getByLabelText("Separator (may be empty)"));
    await userEvent.clear(screen.getByLabelText("Digits (0 to 6)"));
    await userEvent.type(screen.getByLabelText("Digits (0 to 6)"), "4");
    await userEvent.clear(screen.getByLabelText("First number"));
    await userEvent.type(screen.getByLabelText("First number"), "100");
    await userEvent.selectOptions(screen.getByLabelText("Restart the sequence"), "weekly");
    await userEvent.clear(screen.getByLabelText("Reset time (site time)"));
    await userEvent.type(screen.getByLabelText("Reset time (site time)"), "04:30");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText(/3 tokens are waiting here\. They keep the numbers they already have/)).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "PUT" && c.path === "/service-groups/g1/numbering-rule"))).toEqual({
      prefix_source: "fixed",
      fixed_prefix: "VIP",
      sequence_start: 100,
      padding: 4,
      reset_boundary: "weekly",
      reset_time: "04:30",
      separator: "",
    });
    expect(await screen.findByText("Prefix: fixed text VIP · Separator: (none) · Digits: 4 · First number: 100 · Restarts every week (Monday) at 04:30")).toBeInTheDocument();
  });

  it("does not offer a reset time for a sequence that never restarts, and names the fields the API refuses", async () => {
    fakeApi(
      { rules: [] },
      {
        "PUT /service-groups/g1/numbering-rule": () =>
          json(400, { error: { code: "validation_failed", message: "x", trace_id: "t", details: { fields: [{ field: "fixed_prefix", code: "NotBlank" }] } } }),
      },
    );
    renderApp(<NumberingAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Set rule Outpatient" }));
    expect(screen.getByLabelText("Reset time (site time)")).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Restart the sequence"), "never");
    expect(screen.queryByLabelText("Reset time (site time)")).not.toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText("Prefix comes from"), "fixed");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText(/The request contains invalid data\. Check these fields: Fixed prefix/)).toBeInTheDocument();
  });

  it("edits an existing rule from its current values and removes it again", async () => {
    const state = { rules: [RULE], waiting: 0 };
    const calls = fakeApi(state);
    renderApp(<NumberingAdmin />);

    await userEvent.click(await screen.findByRole("button", { name: "Edit rule Outpatient" }));
    expect(screen.getByLabelText("Reset time (site time)")).toHaveValue("04:00");
    expect(screen.getByLabelText("Digits (0 to 6)")).toHaveValue(3);
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByLabelText("Digits (0 to 6)")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Remove rule Outpatient" }));

    expect(await screen.findAllByText(/^No rule: the default applies/)).toHaveLength(3);
    expect(screen.getByText("Saved. No tokens are waiting here; tokens issued from now on follow the rule.")).toBeInTheDocument();
    expect(calls.some((c) => c.method === "DELETE" && c.path === "/service-groups/g1/numbering-rule")).toBe(true);
  });

  it("previews the next Token number of a group's services and of one service without changing anything", async () => {
    const calls = fakeApi(
      { rules: [RULE] },
      {
        "GET /service-groups/g1/numbering-preview": () =>
          json(200, {
            items: [
              { service_id: "v1", token_number: "OPD-042", prefix: "OPD", sequence: 42, reset_key: "2026-09-19", next_reset_at: "2026-09-19T22:00:00Z", rule_source: "service_group" },
              { service_id: "v2", token_number: "OPD-042", prefix: "OPD", sequence: 42, reset_key: "2026-09-19", next_reset_at: null, rule_source: "service_group" },
            ],
          }),
        "GET /services/v1/numbering-preview": () =>
          json(200, { items: [{ service_id: "v1", token_number: "CON-007", prefix: "CON", sequence: 7, reset_key: "never", next_reset_at: null, rule_source: "default" }] }),
      },
    );
    renderApp(<NumberingAdmin />);

    await userEvent.click(await screen.findByRole("button", { name: "Preview next number Outpatient" }));
    expect(await screen.findByText("Next Token number for Consultation: OPD-042")).toBeInTheDocument();
    expect(screen.getByText("Next Token number for Check-up: OPD-042")).toBeInTheDocument();
    expect(screen.getByText("This sequence never restarts.")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Preview next number Consultation" }));
    expect(await screen.findByText("Next Token number for Consultation: CON-007")).toBeInTheDocument();
    expect(calls.filter((c) => c.method !== "GET" && c.path !== "/auth/refresh")).toEqual([]);
  });

  it("shows Bangla labels and keeps the Token number in Western Arabic digits (FR-I18N-020)", async () => {
    fakeApi(
      { rules: [RULE] },
      {
        "GET /service-groups/g1/numbering-preview": () =>
          json(200, { items: [{ service_id: "v1", token_number: "OPD-042", prefix: "OPD", sequence: 42, reset_key: "2026-09-19", next_reset_at: null, rule_source: "service_group" }] }),
      },
    );
    renderApp(<NumberingAdmin />, ["bn-BD"]);

    expect(await screen.findByText("Main campus-এর টোকেন নম্বরিং")).toBeInTheDocument();
    const row = (await screen.findByRole("button", { name: /নিয়ম সম্পাদনা/ })).closest("li")!;
    expect(within(row).getByText(/প্রিফিক্স: সেবা গ্রুপের প্রিফিক্স/)).toBeInTheDocument();
    await userEvent.click(within(row).getByRole("button", { name: /পরের নম্বর দেখুন/ }));

    await waitFor(() => expect(screen.getByText(/পরের টোকেন নম্বর: OPD-042/)).toBeInTheDocument());
  });

  it("links to the screen from the home screen for an administrator only; the API enforces access either way", async () => {
    const home = (roles: string[]): Routes => ({
      "POST /auth/refresh": () => json(200, { access_token: "tok", token_type: "Bearer", expires_in: 900 }),
      "GET /auth/me": () => json(200, { id: "u1", username: "asha", display_name: "Asha", preferred_language: null, roles, sites: ["s1"], groups: [] }),
      "GET /health/dependencies": () => json(200, { status: "up", dependencies: {} }),
    });
    stubApi(home(["org_admin"]));
    const admin = renderApp(<Home />);
    const link = await screen.findByRole("link", { name: "Token numbering" });
    expect(link.getAttribute("href")).toMatch(/^\/numbering\/?$/);
    admin.unmount();

    stubApi(home(["reception_operator"]));
    renderApp(<Home />);
    await screen.findByRole("link", { name: "Reception desk" });
    expect(screen.queryByRole("link", { name: "Token numbering" })).not.toBeInTheDocument();
  });
});
