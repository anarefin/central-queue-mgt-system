import type { BreakType, CounterSession, SessionBreak, SessionCounterOption, SessionOutcome, SessionTicket, TransferResult, TransferTargets } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Home from "../app/page";
import { FakeWebSocket, json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";

const router = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const STAMP = "2026-09-19T10:00:00+06:00";
const TOKENS = { access_token: "tok", token_type: "Bearer", expires_in: 900 };
const ME = { id: "u1", username: "rina", display_name: "Rina Akter", preferred_language: null as string | null, roles: ["agent"], sites: ["s1"], groups: [] };
const NOT_FOUND = { error: { code: "not_found", message: "x", trace_id: "t" } };

const COUNTER = { id: "c1", label: "Desk 1", zone_id: "z1", zone_name: "Hall", site_id: "s1" };
const SERVICES = [
  { id: "v1", name_i18n: { en: "Consultation", bn: "পরামর্শ" }, preference_weight: 1 },
  { id: "v2", name_i18n: { en: "Laboratory", bn: "ল্যাব" }, preference_weight: 2 },
];
const OUTCOMES: SessionOutcome[] = [
  { id: "o1", code: "resolved", label_i18n: { en: "Resolved", bn: "সমাধান হয়েছে" } },
  { id: "o2", code: "referred", label_i18n: { en: "Referred" } },
];

function ticket(over: Partial<SessionTicket> = {}): SessionTicket {
  return {
    id: "t1",
    token_number: "S-042",
    state: "called",
    version: 1,
    service: { id: "v1", name_i18n: { en: "Consultation", bn: "পরামর্শ" } },
    origin_channel: "reception",
    priority_class: null,
    queued_at: STAMP,
    called_at: STAMP,
    served_at: null,
    wait_seconds: 305,
    announce_count: 0,
    announce_limit: 3,
    miss_count: 0,
    miss_limit: 2,
    outcomes: OUTCOMES,
    ...over,
  };
}

function session(over: Partial<CounterSession> = {}): CounterSession {
  return { id: "s1", counter: COUNTER, agent_id: "u1", state: "open", opened_at: STAMP, closed_at: null, services: SERVICES, ticket: null, held: [], hold_limit: 3, break: null, ...over };
}

const OPTIONS: SessionCounterOption[] = [
  { counter: COUNTER, occupied: false, services: SERVICES },
  { counter: { ...COUNTER, id: "c2", label: "Desk 2" }, occupied: true, services: SERVICES },
];

const AUTH: Routes = { "POST /auth/refresh": () => json(200, TOKENS), "GET /auth/me": () => json(200, ME) };

function refusal(status: number, reason: string) {
  return json(status, { error: { code: "conflict", message: "x", trace_id: "t", details: { reason } } });
}

function count(calls: Recorded[], route: string): number {
  return calls.filter((c) => `${c.method} ${c.path}` === route).length;
}

function ifMatch(call: Recorded | undefined): string | undefined {
  return (call?.init.headers as Record<string, string> | undefined)?.["If-Match"];
}

function body(call: Recorded | undefined): unknown {
  return JSON.parse(String(call?.init.body));
}

beforeEach(() => router.replace.mockReset());
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("opening a session (FR-AGT-001, FR-AGT-003)", () => {
  it("offers the counters the API allows, says which are in use, and serves all their services unless told otherwise", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(404, NOT_FOUND),
      "GET /sessions/options": () => json(200, { items: OPTIONS }),
      "POST /sessions": () => json(201, session()),
    });
    const user = userEvent.setup();
    renderApp(<Home />);

    const desk1 = await screen.findByRole("radio", { name: /Desk 1 — Hall/ });
    expect(screen.getByRole("radio", { name: /Desk 2 — Hall/ })).toBeDisabled();
    expect(screen.getByText("In use")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Open session" })).toBeDisabled();

    await user.click(desk1);
    expect(screen.getByRole("checkbox", { name: /Consultation/ })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /Laboratory/ })).toBeChecked();
    await user.click(screen.getByRole("button", { name: "Open session" }));

    expect(await screen.findByText("Counter Desk 1")).toBeInTheDocument();
    expect(body(calls.find((c) => c.method === "POST" && c.path === "/sessions"))).toEqual({ counter_id: "c1" });
  });

  it("sends only the chosen services and cannot open with none chosen", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(404, NOT_FOUND),
      "GET /sessions/options": () => json(200, { items: OPTIONS }),
      "POST /sessions": () => json(201, session({ services: [SERVICES[0]!] })),
    });
    const user = userEvent.setup();
    renderApp(<Home />);

    await user.click(await screen.findByRole("radio", { name: /Desk 1/ }));
    await user.click(screen.getByRole("checkbox", { name: /Laboratory/ }));
    await user.click(screen.getByRole("checkbox", { name: /Consultation/ }));
    expect(screen.getByRole("button", { name: "Open session" })).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: /Consultation/ }));
    await user.click(screen.getByRole("button", { name: "Open session" }));

    await screen.findByText("Counter Desk 1");
    expect(body(calls.find((c) => c.method === "POST" && c.path === "/sessions"))).toEqual({ counter_id: "c1", service_ids: ["v1"] });
  });

  it("says so when no counter is available and shows a refusal by its reason, then reads the list again", async () => {
    stubApi({ ...AUTH, "GET /sessions/current": () => json(404, NOT_FOUND), "GET /sessions/options": () => json(200, { items: [] }) });
    renderApp(<Home />);
    expect(await screen.findByText(/No counter is available to you/)).toBeInTheDocument();
  });

  it("shows why a counter could not be opened", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(404, NOT_FOUND),
      "GET /sessions/options": () => json(200, { items: OPTIONS.slice(0, 1) }),
      "POST /sessions": () => refusal(409, "counter_occupied"),
    });
    const user = userEvent.setup();
    renderApp(<Home />);

    await user.click(await screen.findByRole("radio", { name: /Desk 1/ }));
    await user.click(screen.getByRole("button", { name: "Open session" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Another session is using this counter.");
    await waitFor(() => expect(count(calls, "GET /sessions/options")).toBe(2));
  });
});

describe("serving from the keyboard (FR-AGT-010, FR-AGT-032, NFR-USA-002)", () => {
  it("calls, serves and completes a ticket and closes the session with F2, F4, F5 and F10 alone", async () => {
    let state: CounterSession = session();
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, state),
      "GET /sessions/options": () => json(200, { items: OPTIONS }),
      "POST /sessions/s1/next": () => json(200, (state = session({ ticket: ticket() }))),
      "POST /sessions/s1/serve": () => json(200, (state = session({ ticket: ticket({ state: "serving", version: 2, served_at: STAMP }) }))),
      "POST /sessions/s1/complete": () => json(200, (state = session())),
      "DELETE /sessions/s1": () => json(200, session({ state: "closed", closed_at: STAMP })),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("No ticket in progress. Press F2 to call the next ticket.");
    expect(screen.getByRole("button", { name: /Start service/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /^Complete/ })).toBeDisabled();

    await user.keyboard("{F2}");
    expect(await screen.findByTestId("current-token")).toHaveTextContent("S-042");
    expect(screen.getByText("Called, waiting for the visitor")).toBeInTheDocument();
    expect(screen.getByText(/waited 5 min 5 s/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Call next/ })).toBeDisabled();

    await user.keyboard("{F2}"); // call next is off while a ticket is called (FR-AGT-010)
    expect(count(calls, "POST /sessions/s1/next")).toBe(1);

    await user.keyboard("{F4}");
    await screen.findByText("In service");
    expect(ifMatch(calls.find((c) => c.path === "/sessions/s1/serve"))).toBe('"1"');
    expect(screen.getByLabelText("Outcome")).toHaveFocus();

    await user.keyboard("{F5}"); // no outcome chosen yet: nothing is sent
    expect(await screen.findByRole("alert")).toHaveTextContent("Choose an outcome before completing.");
    expect(count(calls, "POST /sessions/s1/complete")).toBe(0);

    await user.selectOptions(screen.getByLabelText("Outcome"), "Referred");
    await user.keyboard("{F5}");
    await waitFor(() => expect(screen.queryByTestId("current-token")).not.toBeInTheDocument());
    const complete = calls.find((c) => c.path === "/sessions/s1/complete");
    expect(body(complete)).toEqual({ outcome_code_id: "o2" });
    expect(ifMatch(complete)).toBe('"2"');
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    await user.keyboard("{F10}");
    expect(await screen.findByText("Open a counter session")).toBeInTheDocument();
    expect(count(calls, "DELETE /sessions/s1")).toBe(1);
  });

  it("records the note with the outcome and works with the buttons too", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ ticket: ticket({ state: "serving", version: 2 }) })),
      "POST /sessions/s1/complete": () => json(200, session()),
    });
    const user = userEvent.setup();
    renderApp(<Home />);

    await user.selectOptions(await screen.findByLabelText("Outcome"), "Resolved");
    await user.type(screen.getByLabelText("Note (optional)"), "  documents checked ");
    await user.click(screen.getByRole("button", { name: /^Complete/ }));

    await waitFor(() => expect(count(calls, "POST /sessions/s1/complete")).toBe(1));
    expect(body(calls.find((c) => c.path === "/sessions/s1/complete"))).toEqual({ outcome_code_id: "o1", note: "documents checked" });
  });

  it("completes without an outcome when the service has none", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ ticket: ticket({ state: "serving", version: 2, outcomes: [] }) })),
      "POST /sessions/s1/complete": () => json(200, session()),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("In service");
    expect(screen.queryByLabelText("Outcome")).not.toBeInTheDocument();

    await user.keyboard("{F5}");

    await waitFor(() => expect(count(calls, "POST /sessions/s1/complete")).toBe(1));
    expect(body(calls.find((c) => c.path === "/sessions/s1/complete"))).toEqual({});
  });

  it("says nobody is waiting without treating it as a failure", async () => {
    stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session()),
      "POST /sessions/s1/next": () => refusal(409, "no_ticket_waiting"),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText(/No ticket in progress/);

    await user.keyboard("{F2}");

    expect(await screen.findByRole("status")).toHaveTextContent("Nobody is waiting right now.");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("does not send the same key twice while the first is still on its way", async () => {
    let release: (response: Response) => void = () => undefined;
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session()),
      "POST /sessions/s1/next": () => new Promise<Response>((resolve) => (release = resolve)) as unknown as Response,
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText(/No ticket in progress/);

    await user.keyboard("{F2}{F2}{F2}");
    expect(count(calls, "POST /sessions/s1/next")).toBe(1);
    release(json(200, session({ ticket: ticket() })));
    await screen.findByTestId("current-token");
  });
});

describe("re-announce and miss (FR-DSP-028, FR-QUE-050, ADR-0005)", () => {
  it("re-announces a called ticket with F3, keeping it called, and stops offering it at the limit", async () => {
    let state = session({ ticket: ticket({ announce_limit: 2 }) });
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, state),
      "POST /sessions/s1/reannounce": () => {
        const held = state.ticket!;
        return json(200, (state = session({ ticket: ticket({ version: held.version + 1, announce_count: held.announce_count + 1, announce_limit: 2 }) })));
      },
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByTestId("current-token");
    expect(screen.queryByText(/Re-announced/)).not.toBeInTheDocument();

    await user.keyboard("{F3}");
    expect(await screen.findByText("Re-announced 1 of 2 times")).toBeInTheDocument();
    expect(ifMatch(calls.find((c) => c.path === "/sessions/s1/reannounce"))).toBe('"1"');
    expect(screen.getByText("Called, waiting for the visitor")).toBeInTheDocument();
    expect(screen.getByTestId("current-token")).toHaveTextContent("S-042");

    await user.keyboard("{F3}");
    expect(await screen.findByText("Re-announced 2 of 2 times")).toBeInTheDocument();
    expect(ifMatch(calls.filter((c) => c.path === "/sessions/s1/reannounce")[1])).toBe('"2"');
    expect(screen.getByRole("button", { name: /Re-announce/ })).toBeDisabled();

    await user.keyboard("{F3}"); // at the limit: nothing more is sent
    expect(count(calls, "POST /sessions/s1/reannounce")).toBe(2);
  });

  it("does not offer Re-announce or Miss before a ticket is called or once it is in service", async () => {
    stubApi({ ...AUTH, "GET /sessions/current": () => json(200, session({ ticket: ticket({ state: "serving", version: 2 }) })) });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("In service");

    expect(screen.getByRole("button", { name: /Re-announce/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /^Miss/ })).toBeDisabled();
    await user.keyboard("{F3}{F6}");
    expect(screen.getByText("In service")).toBeInTheDocument();
  });

  it("misses the called ticket with F6 and shows the counter free to call the next one", async () => {
    let state = session({ ticket: ticket() });
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, state),
      "POST /sessions/s1/miss": () => json(200, (state = session())),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByTestId("current-token");
    expect(screen.queryByText(/Another miss closes this ticket/)).not.toBeInTheDocument();

    await user.keyboard("{F6}");

    await waitFor(() => expect(screen.queryByTestId("current-token")).not.toBeInTheDocument());
    expect(screen.getByText(/No ticket in progress/)).toBeInTheDocument();
    expect(ifMatch(calls.find((c) => c.path === "/sessions/s1/miss"))).toBe('"1"');
    expect(screen.getByRole("button", { name: /Call next/ })).toBeEnabled();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("warns before the Miss that would close the ticket as a no-show", async () => {
    stubApi({ ...AUTH, "GET /sessions/current": () => json(200, session({ ticket: ticket({ miss_count: 2 }) })) });
    renderApp(<Home />);

    expect(await screen.findByText("Missed 2 of 2 times already. Another miss closes this ticket as a no-show.")).toBeInTheDocument();
  });

  it("returns to the counter list once missing the last ticket closes the session", async () => {
    stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ state: "closing", ticket: ticket() })),
      "POST /sessions/s1/miss": () => json(200, session({ state: "closed", closed_at: STAMP })),
      "GET /sessions/options": () => json(200, { items: OPTIONS }),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByTestId("current-token");

    await user.keyboard("{F6}");

    expect(await screen.findByText("Open a counter session")).toBeInTheDocument();
  });

  it("says why the API refused a Re-announce, in the reader's language, and reads the session again", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ ticket: ticket() })),
      "POST /sessions/s1/reannounce": () => refusal(409, "reannounce_limit_reached"),
    });
    const user = userEvent.setup();
    renderApp(<Home />, ["bn-BD"]);
    await screen.findByTestId("current-token");

    await user.keyboard("{F3}");

    expect(await screen.findByRole("alert")).toHaveTextContent("এই টিকিটটি অনুমোদিত সর্বোচ্চ বার আবার ঘোষণা করা হয়েছে।");
    expect(count(calls, "GET /sessions/current")).toBe(2);
    expect(screen.getByRole("button", { name: /আবার ঘোষণা/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /অনুপস্থিত/ })).toBeInTheDocument();
  });
});

describe("hold and held by me (FR-AGT-013, ADR-0008, §19.3)", () => {
  const serving = (over: Partial<SessionTicket> = {}) => ticket({ state: "serving", version: 2, served_at: STAMP, ...over });
  const parked = (id: string, token: string, version = 3) => ticket({ id, token_number: token, state: "held", version, served_at: STAMP });

  it("holds the ticket in service with F8, lists it under held by me and leaves the counter free to call next", async () => {
    let state = session({ ticket: serving() });
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, state),
      "POST /sessions/s1/hold": () => json(200, (state = session({ held: [parked("t1", "S-042")] }))),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("In service");
    expect(screen.queryByText(/Held by me/)).not.toBeInTheDocument();

    await user.keyboard("{F8}");

    await waitFor(() => expect(screen.queryByTestId("current-token")).not.toBeInTheDocument());
    expect(ifMatch(calls.find((c) => c.path === "/sessions/s1/hold"))).toBe('"2"');
    expect(calls.find((c) => c.path === "/sessions/s1/hold")?.init.body).toBeUndefined();
    expect(screen.getByText("Held by me (1 of 3)")).toBeInTheDocument();
    expect(within(screen.getByTestId("held-t1")).getByText("S-042")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Call next/ })).toBeEnabled();
    expect(screen.getByRole("button", { name: /^Hold/ })).toBeDisabled();
  });

  it("offers Hold only for a ticket in service, in an open session, below the hold limit", async () => {
    stubApi({ ...AUTH, "GET /sessions/current": () => json(200, session({ ticket: ticket() })) });
    const user = userEvent.setup();
    const first = renderApp(<Home />);
    await screen.findByText("Called, waiting for the visitor");
    expect(screen.getByRole("button", { name: /^Hold/ })).toBeDisabled();
    await user.keyboard("{F8}");
    expect(screen.getByText("Called, waiting for the visitor")).toBeInTheDocument();
    first.unmount();

    const held = [parked("h1", "S-001"), parked("h2", "S-002"), parked("h3", "S-003")];
    const calls = stubApi({ ...AUTH, "GET /sessions/current": () => json(200, session({ ticket: serving(), held })) });
    renderApp(<Home />);
    await screen.findByText("In service");
    expect(screen.getByText("Held by me (3 of 3)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Hold/ })).toBeDisabled();
    await user.keyboard("{F8}");
    expect(count(calls, "POST /sessions/s1/hold")).toBe(0);
  });

  it("resumes a held ticket from the list, only while nothing else is in progress", async () => {
    stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ ticket: serving({ id: "t2", token_number: "S-043", version: 4 }), held: [parked("t1", "S-042")] })),
    });
    const busy = renderApp(<Home />);
    await screen.findByText("In service");
    expect(screen.getByRole("button", { name: "Resume S-042" })).toBeDisabled();
    busy.unmount();

    let state = session({ held: [parked("t1", "S-042")] });
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, state),
      "POST /sessions/s1/hold": () => json(200, (state = session({ ticket: serving({ version: 4 }) }))),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await user.click(await screen.findByRole("button", { name: "Resume S-042" }));

    expect(await screen.findByTestId("current-token")).toHaveTextContent("S-042");
    const resume = calls.find((c) => c.path === "/sessions/s1/hold");
    expect(body(resume)).toEqual({ ticket_id: "t1" });
    expect(ifMatch(resume)).toBe('"3"');
    expect(screen.getByText("In service")).toBeInTheDocument();
    expect(screen.queryByText(/Held by me/)).not.toBeInTheDocument();
  });

  it("names the held tickets when a close is refused and says how to finish closing", async () => {
    let state = session({ held: [parked("t1", "S-042")] });
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, state),
      "DELETE /sessions/s1": () => {
        state = { ...state, state: "closing" };
        return refusal(409, "held_tickets_remaining");
      },
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByTestId("held-t1");

    await user.keyboard("{F10}");

    expect(await screen.findByRole("alert")).toHaveTextContent("Resume and complete your held tickets before closing.");
    expect(await screen.findByText("Resume and complete each held ticket to finish closing.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Resume S-042" })).toBeEnabled();
    expect(count(calls, "GET /sessions/current")).toBe(2);
  });

  it("says why the API refused a Hold, in the reader's language, and reads the session again", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ ticket: serving() })),
      "POST /sessions/s1/hold": () => refusal(409, "hold_limit_reached"),
    });
    const user = userEvent.setup();
    renderApp(<Home />, ["bn-BD"]);
    await screen.findByTestId("current-token");

    await user.keyboard("{F8}");

    expect(await screen.findByRole("alert")).toHaveTextContent("এই সেশনে অনুমোদিত সর্বোচ্চ সংখ্যক টিকিট ইতিমধ্যে ধরে রাখা আছে।");
    expect(count(calls, "GET /sessions/current")).toBe(2);
  });
});

describe("transfer to a successor ticket (FR-QUE-052, FR-QUE-053, ADR-0006, UAT U5)", () => {
  const serving = (over: Partial<SessionTicket> = {}) => ticket({ state: "serving", version: 2, served_at: STAMP, ...over });
  const TARGETS: TransferTargets = {
    services: [
      { id: "v1", name_i18n: { en: "Consultation", bn: "পরামর্শ" } },
      { id: "v2", name_i18n: { en: "Laboratory", bn: "ল্যাব" } },
    ],
    counters: [
      { id: "c2", label: "Desk 2", zone_name: "Hall", service_ids: ["v2"] },
      { id: "c3", label: "Desk 3", zone_name: "Hall", service_ids: ["v1"] },
    ],
    agents: [
      { id: "u2", name: "Karim", service_ids: ["v1", "v2"] },
      { id: "u3", name: "Salma", service_ids: ["v1"] },
    ],
  };
  const done = (over: Partial<TransferResult["successor"]> = {}, next: CounterSession = session()): TransferResult => ({
    predecessor: { id: "t1", token_number: "S-042", state: "transferred" },
    successor: {
      id: "t2",
      token_number: "S-042",
      state: "waiting",
      service: { id: "v2", name_i18n: { en: "Laboratory", bn: "ল্যাব" } },
      visit_id: "visit1",
      predecessor_ticket_id: "t1",
      head_start_minutes: 20,
      position: 1,
      ...over,
    },
    session: next,
  });
  const transferCall = (calls: Recorded[]) => calls.find((c) => c.method === "POST" && c.path === "/tickets/t1/transfer");

  it("transfers the ticket in service to another service with F7, a note and the ticket version, and the counter is free again", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ ticket: serving() })),
      "GET /sessions/s1/transfer-targets": () => json(200, TARGETS),
      "POST /tickets/t1/transfer": () => json(200, done()),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("In service");
    expect(screen.queryByLabelText("Send to service")).not.toBeInTheDocument();

    await user.keyboard("{F7}");
    await user.selectOptions(await screen.findByLabelText("Send to service"), "v2");
    expect(screen.getByRole("button", { name: "Transfer" }), "the note is required").toBeDisabled();
    await user.type(screen.getByLabelText("Note for the next agent (required)"), "  Needs a blood test ");
    await user.click(screen.getByRole("button", { name: "Transfer" }));

    expect(await screen.findByRole("status")).toHaveTextContent("S-042 was transferred to Laboratory. It keeps its token number and its place in the queue.");
    expect(body(transferCall(calls))).toEqual({ service_id: "v2", note: "Needs a blood test" });
    expect(ifMatch(transferCall(calls))).toBe('"2"');
    expect(screen.queryByTestId("current-token")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Send to service")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Call next/ })).toBeEnabled();
  });

  it("sends the ticket to a specific agent or counter of the chosen service, listing only those that serve it", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ ticket: serving() })),
      "GET /sessions/s1/transfer-targets": () => json(200, TARGETS),
      "POST /tickets/t1/transfer": () => json(200, done({ agent_id: "u2" })),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("In service");
    await user.click(screen.getByRole("button", { name: /^Transfer F7/ }));
    await user.selectOptions(await screen.findByLabelText("Send to service"), "v2");

    await user.click(screen.getByRole("radio", { name: "A specific agent" }));
    expect(within(screen.getByLabelText("Agent")).queryByRole("option", { name: "Salma" }), "Salma does not serve the laboratory").not.toBeInTheDocument();
    await user.type(screen.getByLabelText("Note for the next agent (required)"), "Second opinion");
    expect(screen.getByRole("button", { name: "Transfer" }), "an agent must be chosen").toBeDisabled();
    await user.selectOptions(screen.getByLabelText("Agent"), "u2");
    await user.click(screen.getByRole("button", { name: "Transfer" }));

    await screen.findByRole("status");
    expect(body(transferCall(calls))).toEqual({ service_id: "v2", agent_id: "u2", note: "Second opinion" });
  });

  it("sends a counter target with the counter and not the agent", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ ticket: serving() })),
      "GET /sessions/s1/transfer-targets": () => json(200, TARGETS),
      "POST /tickets/t1/transfer": () => json(200, done({ counter_id: "c2" })),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("In service");
    await user.keyboard("{F7}");
    await user.selectOptions(await screen.findByLabelText("Send to service"), "v2");
    await user.click(screen.getByRole("radio", { name: "A specific counter" }));
    expect(within(screen.getByLabelText("Counter")).getAllByRole("option").map((o) => o.textContent)).toEqual(["Choose…", "Desk 2 — Hall"]);
    await user.selectOptions(screen.getByLabelText("Counter"), "c2");
    await user.type(screen.getByLabelText("Note for the next agent (required)"), "Scanner");
    await user.click(screen.getByRole("button", { name: "Transfer" }));

    await screen.findByRole("status");
    expect(body(transferCall(calls))).toEqual({ service_id: "v2", counter_id: "c2", note: "Scanner" });
  });

  it("does not offer a transfer to the same service unless it is narrowed to a counter or an agent", async () => {
    stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ ticket: serving() })),
      "GET /sessions/s1/transfer-targets": () => json(200, TARGETS),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("In service");
    await user.keyboard("{F7}");
    await user.selectOptions(await screen.findByLabelText("Send to service"), "v1");
    await user.type(screen.getByLabelText("Note for the next agent (required)"), "Specialist");

    expect(screen.getByText("Choose another service, or a specific counter or agent.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Transfer" })).toBeDisabled();
    await user.click(screen.getByRole("radio", { name: "A specific counter" }));
    await user.selectOptions(screen.getByLabelText("Counter"), "c3");
    expect(screen.getByRole("button", { name: "Transfer" })).toBeEnabled();
  });

  it("is offered only for a ticket in service, and while the panel is open the other keys do not act", async () => {
    stubApi({ ...AUTH, "GET /sessions/current": () => json(200, session({ ticket: ticket() })) });
    const user = userEvent.setup();
    const first = renderApp(<Home />);
    await screen.findByText("Called, waiting for the visitor");
    expect(screen.getByRole("button", { name: /^Transfer F7/ })).toBeDisabled();
    await user.keyboard("{F7}");
    expect(screen.queryByLabelText("Send to service")).not.toBeInTheDocument();
    first.unmount();

    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ ticket: serving() })),
      "GET /sessions/s1/transfer-targets": () => json(200, TARGETS),
    });
    renderApp(<Home />);
    await screen.findByText("In service");
    expect(screen.getByRole("button", { name: /^Transfer F7/ })).toBeEnabled();
    await user.keyboard("{F7}");
    await screen.findByLabelText("Send to service");

    await user.keyboard("{F5}{F8}{F10}");
    expect(calls.filter((c) => c.method !== "GET" && c.path !== "/auth/refresh"), "nothing was sent").toEqual([]);
    await user.keyboard("{F7}");
    expect(screen.queryByLabelText("Send to service")).not.toBeInTheDocument();
    await user.keyboard("{F7}");
    await user.type(await screen.findByLabelText("Note for the next agent (required)"), "x{Escape}");
    expect(screen.queryByLabelText("Send to service")).not.toBeInTheDocument();
    expect(screen.getByText("In service")).toBeInTheDocument();
  });

  it("says why the API refused a transfer, in the reader's language, keeps the panel and reads the session again", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ ticket: serving() })),
      "GET /sessions/s1/transfer-targets": () => json(200, TARGETS),
      "POST /tickets/t1/transfer": () => refusal(409, "transfer_target_inactive"),
    });
    const user = userEvent.setup();
    renderApp(<Home />, ["bn-BD"]);
    await screen.findByTestId("current-token");
    await user.keyboard("{F7}");
    await user.selectOptions(await screen.findByLabelText("যে সেবায় পাঠাবেন"), "v2");
    await user.type(screen.getByLabelText("পরবর্তী এজেন্টের জন্য নোট (আবশ্যক)"), "ল্যাব");
    await user.click(screen.getByRole("button", { name: "স্থানান্তর করুন" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("সেবা, কাউন্টার বা এজেন্টটি সক্রিয় নয়।");
    expect(count(calls, "GET /sessions/current")).toBe(2);
    expect(screen.getByLabelText("যে সেবায় পাঠাবেন")).toBeInTheDocument();
    expect(screen.getByTestId("current-token")).toHaveTextContent("S-042");
  });

  it("returns to the counter list once transferring the last ticket closes the session", async () => {
    stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ state: "closing", ticket: serving() })),
      "GET /sessions/s1/transfer-targets": () => json(200, TARGETS),
      "POST /tickets/t1/transfer": () => json(200, done({}, session({ state: "closed", closed_at: STAMP }))),
      "GET /sessions/options": () => json(200, { items: OPTIONS }),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("In service");
    await user.keyboard("{F7}");
    await user.selectOptions(await screen.findByLabelText("Send to service"), "v2");
    await user.type(screen.getByLabelText("Note for the next agent (required)"), "Lab");
    await user.click(screen.getByRole("button", { name: "Transfer" }));

    expect(await screen.findByText("Open a counter session")).toBeInTheDocument();
  });
});

describe("breaks (F9, FR-AGT-020, FR-AGT-021, FR-AGT-022, SRS §19.3)", () => {
  const TYPES: BreakType[] = [
    { id: "b1", name_i18n: { en: "Lunch", bn: "দুপুরের খাবার" }, max_minutes: 30, active: true, created_at: STAMP, updated_at: STAMP },
    { id: "b2", name_i18n: { en: "Meeting" }, max_minutes: null, active: true, created_at: STAMP, updated_at: STAMP },
    { id: "b3", name_i18n: { en: "Retired" }, max_minutes: 10, active: false, created_at: STAMP, updated_at: STAMP },
  ];

  const parked = (id: string, token: string) => ticket({ id, token_number: token, state: "held", version: 3, served_at: STAMP });

  function onBreak(over: Partial<SessionBreak> = {}, startedAt = new Date().toISOString()): SessionBreak {
    return { id: "r1", type: { id: "b1", name_i18n: TYPES[0]!.name_i18n, max_minutes: 30 }, started_at: startedAt, ...over };
  }

  it("offers the active break types with F9, starts the chosen one and shows the break, its clock and that nothing is assigned", async () => {
    let state = session();
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, state),
      "GET /break-types": () => json(200, { items: TYPES }),
      "POST /sessions/s1/break": () => json(200, (state = session({ state: "on_break", break: onBreak() }))),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("No ticket in progress. Press F2 to call the next ticket.");

    await user.keyboard("{F9}");

    const choose = await screen.findByLabelText("Break type");
    expect(within(choose).getAllByRole("option").map((o) => o.textContent)).toEqual(["Choose a break type", "Lunch (up to 30 min)", "Meeting"]);
    expect(screen.getByRole("button", { name: "Start break" })).toBeDisabled();
    await user.selectOptions(choose, "b1");
    await user.click(screen.getByRole("button", { name: "Start break" }));

    expect(await screen.findByText("On break: Lunch")).toBeInTheDocument();
    expect(body(calls.find((c) => c.method === "POST" && c.path === "/sessions/s1/break"))).toEqual({ break_type_id: "b1" });
    expect(screen.getByRole("timer")).toHaveTextContent(/On break for 0 min \d+ s · Maximum 30 min/);
    expect(screen.getByText(/no new tickets are assigned/)).toBeInTheDocument();
    expect(screen.getAllByText("On break").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: /Call next/ })).toBeDisabled();
    expect(screen.queryByLabelText("Break type")).not.toBeInTheDocument();
  });

  it("ends the break with F9 and the desk takes calls again", async () => {
    let state = session({ state: "on_break", break: onBreak() });
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, state),
      "POST /sessions/s1/break": () => json(200, (state = session())),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("On break: Lunch");
    await user.keyboard("{F2}");
    expect(count(calls, "POST /sessions/s1/next"), "no call while on a break").toBe(0);

    await user.keyboard("{F9}");

    await screen.findByText("No ticket in progress. Press F2 to call the next ticket.");
    const end = calls.find((c) => c.method === "POST" && c.path === "/sessions/s1/break");
    expect(end?.init.body, "no type: ends the break").toBeUndefined();
    expect(screen.queryByText("On break: Lunch")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Call next/ })).toBeEnabled();
  });

  it("restores a break after a refresh and warns once it has run past its maximum", async () => {
    const started = new Date(Date.now() - 45 * 60_000).toISOString();
    stubApi({ ...AUTH, "GET /sessions/current": () => json(200, session({ state: "on_break", break: onBreak({}, started) })) });
    renderApp(<Home />);

    expect(await screen.findByText("On break: Lunch")).toBeInTheDocument();
    expect(screen.getByRole("timer")).toHaveTextContent(/On break for 45 min/);
    expect(screen.getByText("This break has run past its maximum of 30 min. Please return to your desk.")).toBeInTheDocument();
  });

  it("does not warn while a break is within its maximum or its type has none", async () => {
    stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ state: "on_break", break: onBreak({ type: { id: "b2", name_i18n: { en: "Meeting" }, max_minutes: null } }, new Date(Date.now() - 600 * 60_000).toISOString()) })),
    });
    renderApp(<Home />);

    expect(await screen.findByText("On break: Meeting")).toBeInTheDocument();
    expect(screen.queryByText(/has run past its maximum/)).not.toBeInTheDocument();
  });

  it("is not offered while a ticket is in progress, and F9 then sends nothing", async () => {
    const calls = stubApi({ ...AUTH, "GET /sessions/current": () => json(200, session({ ticket: ticket() })) });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("Called, waiting for the visitor");
    expect(screen.getByRole("button", { name: /^Break F9/ })).toBeDisabled();

    await user.keyboard("{F9}");

    expect(screen.queryByLabelText("Break type")).not.toBeInTheDocument();
    expect(calls.filter((c) => c.method !== "GET" && c.path !== "/auth/refresh")).toEqual([]);
    expect(count(calls, "GET /break-types")).toBe(0);
  });

  it("is offered with a ticket held, since a held ticket is parked and not in progress", async () => {
    stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ held: [parked("t1", "S-042")] })),
      "GET /break-types": () => json(200, { items: TYPES }),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByTestId("held-t1");
    expect(screen.getByRole("button", { name: /^Break F9/ })).toBeEnabled();

    await user.keyboard("{F9}");

    expect(await screen.findByLabelText("Break type")).toBeInTheDocument();
  });

  it("while the panel is open the other keys do not act, F9 and Esc close it, and it says so when no break type exists", async () => {
    const calls = stubApi({ ...AUTH, "GET /sessions/current": () => json(200, session()), "GET /break-types": () => json(200, { items: [TYPES[2]!] }) });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("No ticket in progress. Press F2 to call the next ticket.");
    await user.keyboard("{F9}");
    expect(await screen.findByText("No break types are set up yet. Ask an admin to add one.")).toBeInTheDocument();

    await user.keyboard("{F2}{F10}");
    expect(calls.filter((c) => c.method !== "GET" && c.path !== "/auth/refresh"), "nothing was sent").toEqual([]);
    await user.keyboard("{F9}");
    expect(screen.queryByText("Start a break")).not.toBeInTheDocument();
    await user.keyboard("{F9}");
    await screen.findByText("Start a break");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByText("Start a break")).not.toBeInTheDocument();
  });

  it("says why the API refused a break, in the reader's language, and reads the session again", async () => {
    let state = session();
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, state),
      "GET /break-types": () => json(200, { items: TYPES }),
      "POST /sessions/s1/break": () => {
        state = session({ ticket: ticket() });
        return refusal(409, "ticket_in_progress");
      },
    });
    const user = userEvent.setup();
    renderApp(<Home />, ["bn-BD"]);
    await screen.findByText("কাউন্টার কনসোল");
    await user.keyboard("{F9}");
    await user.selectOptions(await screen.findByLabelText("বিরতির ধরন"), "b2");
    await user.click(screen.getByRole("button", { name: "বিরতি শুরু" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("আগে চলমান টিকিটটি শেষ করুন।");
    expect(await screen.findByTestId("current-token")).toHaveTextContent("S-042");
    expect(count(calls, "GET /sessions/current")).toBe(2);
  });

  it("reads the session again when an admin put the agent on a break or back, and not for its own echo", async () => {
    let state = session();
    const calls = stubApi({ ...AUTH, "GET /sessions/current": () => json(200, state) });
    renderApp(<Home />);
    const socket = await connected();
    await screen.findByText("No ticket in progress. Press F2 to call the next ticket.");
    socket.say(counterSnapshot({ id: "s1", state: "open" }, null));

    socket.say(hubEvent("counter:c1", 1, "session.break_ended", { session_id: "s1", state: "open" }));
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(count(calls, "GET /sessions/current"), "the screen already shows an open session").toBe(1);

    state = session({ state: "on_break", break: onBreak() });
    socket.say(hubEvent("counter:c1", 2, "session.break_started", { session_id: "s1", state: "on_break", forced: true }));

    expect(await screen.findByText("On break: Lunch")).toBeInTheDocument();
    expect(count(calls, "GET /sessions/current")).toBe(2);
    state = session();
    socket.say(hubEvent("counter:c1", 3, "session.break_ended", { session_id: "s1", state: "open", forced: true }));
    await waitFor(() => expect(screen.queryByText("On break: Lunch")).not.toBeInTheDocument());
  });

  it("lets the agent close the session from a break with F10", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ state: "on_break", break: onBreak() })),
      "DELETE /sessions/s1": () => json(200, session({ state: "closed", closed_at: STAMP })),
      "GET /sessions/options": () => json(200, { items: OPTIONS }),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("On break: Lunch");

    await user.keyboard("{F10}");

    expect(await screen.findByText("Open a counter session")).toBeInTheDocument();
    expect(count(calls, "DELETE /sessions/s1")).toBe(1);
  });
});

describe("closing (FR-AGT-005, SRS §19.3)", () => {
  it("is refused while a ticket is in progress, and the console shows the session as closing", async () => {
    let state = session({ ticket: ticket() });
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, state),
      "DELETE /sessions/s1": () => {
        state = { ...state, state: "closing" };
        return refusal(409, "ticket_in_progress");
      },
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByTestId("current-token");

    await user.keyboard("{F10}");

    expect(await screen.findByRole("alert")).toHaveTextContent("Finish the ticket in progress first.");
    expect(await screen.findByText(/This session is closing/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Call next/ })).toBeDisabled();
    expect(screen.getByTestId("current-token")).toHaveTextContent("S-042");
    expect(count(calls, "GET /sessions/current")).toBe(2);
  });

  it("returns to the counter list once completing the last ticket closes the session", async () => {
    stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ state: "closing", ticket: ticket({ state: "serving", version: 2, outcomes: [] }) })),
      "POST /sessions/s1/complete": () => json(200, session({ state: "closed", closed_at: STAMP })),
      "GET /sessions/options": () => json(200, { items: OPTIONS }),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("In service");

    await user.keyboard("{F5}");

    expect(await screen.findByText("Open a counter session")).toBeInTheDocument();
  });
});

describe("surviving a refresh, a stale screen and a lost connection (FR-AGT-004, FR-QUE-031)", () => {
  it("restores the session and the ticket in service after a page reload", async () => {
    stubApi({ ...AUTH, "GET /sessions/current": () => json(200, session({ ticket: ticket({ state: "serving", version: 2, served_at: STAMP }) })) });
    renderApp(<Home />);

    expect(await screen.findByTestId("current-token")).toHaveTextContent("S-042");
    expect(screen.getByText("In service")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Call next/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /^Complete/ })).toBeEnabled();
  });

  it("reads the session again when the ticket changed under it, and says so", async () => {
    let state = session({ ticket: ticket() });
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, state),
      "POST /sessions/s1/serve": () => {
        state = session({ ticket: ticket({ state: "serving", version: 3 }) });
        return refusal(409, "version_mismatch");
      },
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByTestId("current-token");

    await user.keyboard("{F4}");

    expect(await screen.findByRole("alert")).toHaveTextContent("The ticket has changed since you last saw it");
    expect(await screen.findByText("In service")).toBeInTheDocument();
    expect(count(calls, "GET /sessions/current")).toBe(2);
  });

  it("keeps what is on screen when the network drops and offers to try again", async () => {
    let online = true;
    stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session({ ticket: ticket() })),
      "POST /sessions/s1/serve": () => {
        if (!online) throw new TypeError("offline");
        return json(200, session({ ticket: ticket({ state: "serving", version: 2 }) }));
      },
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByTestId("current-token");

    online = false;
    await user.keyboard("{F4}");
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not reach the server");
    expect(screen.getByTestId("current-token")).toHaveTextContent("S-042");

    online = true;
    await user.click(screen.getByRole("button", { name: "Try again" }));
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    await user.keyboard("{F4}");
    expect(await screen.findByText("In service")).toBeInTheDocument();
  });

  it("does not answer the function keys before there is a session", async () => {
    const calls = stubApi({ ...AUTH, "GET /sessions/current": () => json(404, NOT_FOUND), "GET /sessions/options": () => json(200, { items: OPTIONS }) });
    const user = userEvent.setup();
    renderApp(<Home />);
    await screen.findByText("Open a counter session");

    await user.keyboard("{F2}{F4}{F5}{F10}");

    expect(calls.filter((c) => c.path.startsWith("/sessions") && (c.method !== "GET" || c.path.startsWith("/sessions/s")))).toEqual([]);
  });
});

describe("language (FR-I18N-001, FR-I18N-020)", () => {
  it("shows the console in Bangla and keeps the token number in Western digits", async () => {
    stubApi({ ...AUTH, "GET /sessions/current": () => json(200, session({ ticket: ticket({ token_number: "S-042" }) })) });
    renderApp(<Home />, ["bn-BD"]);

    expect(await screen.findByText("কাউন্টার কনসোল")).toBeInTheDocument();
    expect(screen.getByTestId("current-token")).toHaveTextContent("S-042");
    expect(screen.getByRole("button", { name: /পরবর্তী ডাকুন/ })).toBeInTheDocument();
    expect(screen.getByText("সেবা: পরামর্শ")).toBeInTheDocument();
  });
});

/** The hub's snapshot of a queue topic. */
function queueSnapshot(service: string, waiting: number, seq = 0) {
  return { frame: "snapshot", topic: `queue:${service}`, seq, epoch: "e1", resync: false, data: { service_id: service, waiting_count: waiting, next: [] } };
}

function counterSnapshot(live: unknown, held: unknown, seq = 0) {
  return { frame: "snapshot", topic: "counter:c1", seq, epoch: "e1", resync: false, data: { counter_id: "c1", label: "Desk 1", session: live, ticket: held } };
}

function hubEvent(topic: string, seq: number, type: string, data: Record<string, unknown>) {
  return { frame: "event", topic, seq, type, occurred_at: STAMP, data };
}

/** Waits for the console to dial the hub, and opens the socket. */
async function connected(): Promise<FakeWebSocket> {
  await waitFor(() => expect(FakeWebSocket.all).toHaveLength(1));
  const socket = FakeWebSocket.all[0]!;
  socket.open();
  return socket;
}

describe("live updates (SRS §21, FR-QUE-080, FR-QUE-082, FR-QUE-084)", () => {
  it("listens to its counter and to the queue of each service it serves, on the access token, and shows the waiting counts moving", async () => {
    stubApi({ ...AUTH, "GET /sessions/current": () => json(200, session()) });
    renderApp(<Home />);
    const socket = await connected();

    expect(socket.protocols).toEqual(["qms.v1", "bearer.tok"]);
    expect(socket.subscribed()).toEqual(["counter:c1", "queue:v1", "queue:v2"]);
    expect(screen.getByTestId("waiting-v1")).toHaveTextContent("Consultation: – waiting"); // not heard from yet

    socket.say(queueSnapshot("v1", 3));
    socket.say(queueSnapshot("v2", 0));
    expect(screen.getByTestId("waiting-v1")).toHaveTextContent("Consultation: 3 waiting");
    expect(screen.getByTestId("waiting-v2")).toHaveTextContent("Laboratory: 0 waiting");

    socket.say(hubEvent("queue:v1", 1, "ticket.issued", { waiting_count: 4 }));
    expect(screen.getByTestId("waiting-v1")).toHaveTextContent("Consultation: 4 waiting");
    socket.say(hubEvent("queue:v1", 2, "ticket.called", { waiting_count: 3 }));
    expect(screen.getByTestId("waiting-v1")).toHaveTextContent("Consultation: 3 waiting");
    socket.say(hubEvent("queue:v1", 1, "ticket.issued", { waiting_count: 4 })); // seen already: applying it again would go backwards
    expect(screen.getByTestId("waiting-v1")).toHaveTextContent("Consultation: 3 waiting");
  });

  it("shows the counts in Bangla digits and words in the Bangla console", async () => {
    stubApi({ ...AUTH, "GET /sessions/current": () => json(200, session()) });
    renderApp(<Home />, ["bn-BD"]);
    const socket = await connected();
    socket.say(queueSnapshot("v1", 3));

    expect(screen.getByTestId("waiting-v1")).toHaveTextContent("পরামর্শ: ৩ জন অপেক্ষায়");
  });

  it("reads the session again when something other than the agent's own action changed the ticket", async () => {
    let state: CounterSession = session({ ticket: ticket({ state: "serving", version: 2 }) });
    const calls = stubApi({ ...AUTH, "GET /sessions/current": () => json(200, state) });
    renderApp(<Home />);
    const socket = await connected();
    expect(await screen.findByTestId("current-token")).toHaveTextContent("S-042");
    socket.say(counterSnapshot({ id: "s1", state: "open" }, { id: "t1", token_number: "S-042", state: "serving", version: 2 }));
    expect(count(calls, "GET /sessions/current")).toBe(1); // the snapshot agrees with the screen

    state = session(); // an admin closed the ticket
    socket.say(hubEvent("counter:c1", 1, "ticket.completed", { ticket_id: "t1", state: "completed", counter_id: "c1" }));

    await waitFor(() => expect(screen.queryByTestId("current-token")).not.toBeInTheDocument());
    expect(count(calls, "GET /sessions/current")).toBe(2);
  });

  it("does not read the session again for the events its own action caused", async () => {
    let state: CounterSession = session();
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, state),
      "POST /sessions/s1/next": () => json(200, (state = session({ ticket: ticket() }))),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    const socket = await connected();
    socket.say(counterSnapshot({ id: "s1", state: "open" }, null));
    await screen.findByText("No ticket in progress. Press F2 to call the next ticket.");

    await user.keyboard("{F2}");
    await screen.findByTestId("current-token");
    socket.say(hubEvent("counter:c1", 1, "ticket.called", { ticket_id: "t1", state: "called", counter_id: "c1" }));
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(count(calls, "GET /sessions/current")).toBe(1);
    expect(screen.getByTestId("current-token")).toHaveTextContent("S-042");
  });

  it("reads the session again after a resync, since the hub could not say what was missed", async () => {
    let state: CounterSession = session();
    const calls = stubApi({ ...AUTH, "GET /sessions/current": () => json(200, state) });
    renderApp(<Home />);
    const socket = await connected();
    await screen.findByText("No ticket in progress. Press F2 to call the next ticket.");

    state = session({ ticket: ticket() });
    socket.say({ ...counterSnapshot({ id: "s1", state: "open" }, { id: "t1", token_number: "S-042", state: "called", version: 1 }, 40), resync: true });

    expect(await screen.findByTestId("current-token")).toHaveTextContent("S-042");
    expect(count(calls, "GET /sessions/current")).toBe(2);
  });

  it("stops listening when the session is closed, and listens again when the next one opens", async () => {
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session()),
      "GET /sessions/options": () => json(200, { items: OPTIONS }),
      "DELETE /sessions/s1": () => json(200, session({ state: "closed", closed_at: STAMP })),
      "POST /sessions": () => json(201, session()),
    });
    const user = userEvent.setup();
    renderApp(<Home />);
    const first = await connected();
    await screen.findByText("No ticket in progress. Press F2 to call the next ticket.");

    await user.keyboard("{F10}");
    expect(await screen.findByText("Open a counter session")).toBeInTheDocument();
    expect(first.closed).toBe(true);
    expect(count(calls, "DELETE /sessions/s1")).toBe(1);

    await user.click(await screen.findByRole("radio", { name: /Desk 1/ }));
    await user.click(screen.getByRole("button", { name: "Open session" }));
    await screen.findByText("Counter Desk 1");
    await waitFor(() => expect(FakeWebSocket.all).toHaveLength(2));
    FakeWebSocket.all[1]!.open();
    expect(FakeWebSocket.all[1]!.subscribed()).toEqual(["counter:c1", "queue:v1", "queue:v2"]);
  });

  it("falls back to polling where WebSocket is blocked, with nothing on screen to say so (FR-QUE-084)", async () => {
    const snapshot = (topic: string, data: Record<string, unknown>) => () => json(200, { topic, seq: 2, epoch: "e1", data });
    const calls = stubApi({
      ...AUTH,
      "GET /sessions/current": () => json(200, session()),
      "GET /stream/snapshot?topic=counter%3Ac1": snapshot("counter:c1", { session: { id: "s1", state: "open" }, ticket: null }),
      "GET /stream/snapshot?topic=queue%3Av1": snapshot("queue:v1", { waiting_count: 6 }),
      "GET /stream/snapshot?topic=queue%3Av2": snapshot("queue:v2", { waiting_count: 1 }),
    });
    renderApp(<Home />);
    await waitFor(() => expect(FakeWebSocket.all).toHaveLength(1));
    FakeWebSocket.all[0]!.fail();
    await waitFor(() => expect(FakeWebSocket.all).toHaveLength(2), { timeout: 3000 }); // the client dials again after a second
    FakeWebSocket.all[1]!.fail();

    await waitFor(() => expect(screen.getByTestId("waiting-v1")).toHaveTextContent("Consultation: 6 waiting"));
    expect(screen.getByTestId("waiting-v2")).toHaveTextContent("Laboratory: 1 waiting");
    expect(calls.filter((c) => c.path.startsWith("/stream/snapshot")).length).toBeGreaterThanOrEqual(3);
    expect(screen.queryByText(/poll|degraded|offline|connection/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
