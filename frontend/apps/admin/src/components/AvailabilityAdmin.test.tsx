import type { AgentAvailability, BreakType } from "@qms/api-client";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { AvailabilityAdmin } from "./AvailabilityAdmin";

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));

const STAMP = "2026-09-19T20:30:00Z";
const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };
const COUNTER = { id: "c1", label: "Desk 1", zone_id: "z1", zone_name: "Hall", site_id: "s1" };
const TYPES: BreakType[] = [
  { id: "b1", name_i18n: { en: "Lunch", bn: "দুপুরের খাবার" }, max_minutes: 30, active: true, created_at: STAMP, updated_at: STAMP },
  { id: "b2", name_i18n: { en: "Retired" }, max_minutes: null, active: false, created_at: STAMP, updated_at: STAMP },
];

function agent(over: Partial<AgentAvailability> = {}): AgentAvailability {
  return { agent_id: "u1", agent_name: "Rina Akter", status: "available", session_id: "s1", counter: COUNTER, break: null, ...over };
}

const ON_BREAK = agent({
  agent_id: "u2",
  agent_name: "Karim Uddin",
  status: "on_break",
  session_id: "s2",
  break: { id: "r1", type: { id: "b1", name_i18n: TYPES[0]!.name_i18n, max_minutes: 30 }, started_at: STAMP },
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function fakeApi(state: { agents: AgentAvailability[] }, extra: Routes = {}): Recorded[] {
  return stubApi({
    ...NO_SESSION,
    "GET /agents/availability": () => json(200, { items: state.agents }),
    "GET /break-types": () => json(200, { items: TYPES }),
    "PUT /agents/u1/availability": (init) => {
      const input = JSON.parse(String(init.body)) as { status: string };
      const updated = agent({ status: "on_break", break: { id: "r9", type: { id: "b1", name_i18n: TYPES[0]!.name_i18n, max_minutes: 30 }, started_at: STAMP } });
      state.agents = state.agents.map((a) => (a.agent_id === "u1" && input.status === "on_break" ? updated : a));
      return json(200, updated);
    },
    "PUT /agents/u2/availability": () => {
      state.agents = state.agents.map((a) => (a.agent_id === "u2" ? agent({ agent_id: "u2", agent_name: "Karim Uddin", session_id: "s2" }) : a));
      return json(200, state.agents.find((a) => a.agent_id === "u2"));
    },
    ...extra,
  });
}

function bodyOf(call: Recorded | undefined): unknown {
  return JSON.parse(String(call?.init.body));
}

describe("agent availability (FR-AGT-024)", () => {
  it("lists the agents with an open session, their counter and whether they are available or on a break", async () => {
    fakeApi({ agents: [agent(), ON_BREAK] });
    renderApp(<AvailabilityAdmin />);

    expect(await screen.findByText("Rina Akter")).toBeInTheDocument();
    expect(screen.getByText("Karim Uddin")).toBeInTheDocument();
    expect(screen.getByText("Available")).toBeInTheDocument();
    expect(screen.getAllByText("On break").length).toBeGreaterThan(0);
    expect(screen.getByText("On break: Lunch")).toBeInTheDocument();
    expect(screen.getAllByText("Counter Desk 1")).toHaveLength(2);
  });

  it("says when nobody has a session open", async () => {
    fakeApi({ agents: [] });
    renderApp(<AvailabilityAdmin />);

    expect(await screen.findByText("No agent has a counter session open.")).toBeInTheDocument();
  });

  it("puts an available agent on a break of a chosen active type, with a reason, and shows the change", async () => {
    const calls = fakeApi({ agents: [agent()] });
    renderApp(<AvailabilityAdmin />);
    const put = await screen.findByRole("button", { name: "Put Rina Akter on break" });
    expect(put).toBeDisabled();

    const choose = screen.getByLabelText("Break type — Rina Akter");
    expect(within(choose).getAllByRole("option").map((o) => o.textContent), "only active types").toEqual(["Choose a break type", "Lunch"]);
    await userEvent.selectOptions(choose, "b1");
    await userEvent.type(screen.getByLabelText("Reason (optional) — Rina Akter"), "Network outage");
    await userEvent.click(put);

    expect(await screen.findByRole("button", { name: "Return Rina Akter to service" })).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "PUT" && c.path === "/agents/u1/availability"))).toEqual({ status: "on_break", break_type_id: "b1", reason: "Network outage" });
  });

  it("returns an agent on a break to service", async () => {
    const calls = fakeApi({ agents: [ON_BREAK] });
    renderApp(<AvailabilityAdmin />);

    await userEvent.click(await screen.findByRole("button", { name: "Return Karim Uddin to service" }));

    expect(await screen.findByRole("button", { name: "Put Karim Uddin on break" })).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "PUT" && c.path === "/agents/u2/availability"))).toEqual({ status: "available" });
  });

  it("says why the API refused a change, in the reader's language, and reads the list again", async () => {
    const calls = fakeApi(
      { agents: [agent()] },
      { "PUT /agents/u1/availability": () => json(409, { error: { code: "conflict", message: "x", trace_id: "t", details: { reason: "ticket_in_progress" } } }) },
    );
    renderApp(<AvailabilityAdmin />, ["bn-BD"]);
    await userEvent.selectOptions(await screen.findByLabelText("বিরতির ধরন — Rina Akter"), "b1");
    await userEvent.click(screen.getByRole("button", { name: "Rina Akter-কে বিরতিতে পাঠান" }));

    expect(await screen.findByText("ওই এজেন্টের একটি টোকেন চলমান। আগে সেটি শেষ করতে হবে।")).toBeInTheDocument();
    expect(calls.filter((c) => c.path === "/agents/availability").length).toBe(2);
  });
});
