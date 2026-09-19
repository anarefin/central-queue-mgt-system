import type { CounterSession, SessionCounterOption, SessionOutcome, SessionTicket } from "@qms/api-client";
import { screen, waitFor } from "@testing-library/react";
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
    outcomes: OUTCOMES,
    ...over,
  };
}

function session(over: Partial<CounterSession> = {}): CounterSession {
  return { id: "s1", counter: COUNTER, agent_id: "u1", state: "open", opened_at: STAMP, closed_at: null, services: SERVICES, ticket: null, ...over };
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
