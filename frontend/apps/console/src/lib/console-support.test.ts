import type { CounterSession, SessionTicket } from "@qms/api-client";
import type { RealtimeUpdate } from "@qms/realtime-client";
import { describe, expect, it } from "vitest";
import { counterMovedOn, counterTopic, queueTopic, waitingFrom } from "./console-support";

const NAME = { en: "Consultation" };
const STAMP = "2026-09-19T10:00:00+06:00";

function ticket(over: Partial<SessionTicket> = {}): SessionTicket {
  return {
    id: "t1",
    token_number: "S-042",
    state: "called",
    version: 1,
    service: { id: "v1", name_i18n: NAME },
    origin_channel: "reception",
    priority_class: null,
    queued_at: STAMP,
    called_at: STAMP,
    served_at: null,
    wait_seconds: 0,
    announce_count: 0,
    announce_limit: 3,
    miss_count: 0,
    miss_limit: 2,
    outcomes: [],
    ...over,
  };
}

function session(over: Partial<CounterSession> = {}): CounterSession {
  return {
    id: "s1",
    counter: { id: "c1", label: "Desk 1", zone_id: "z1", zone_name: "Hall", site_id: "x" },
    agent_id: "u1",
    state: "open",
    opened_at: STAMP,
    closed_at: null,
    services: [],
    ticket: null,
    held: [],
    hold_limit: 3,
    ...over,
  };
}

const snapshot = (data: Record<string, unknown>, resync = false): RealtimeUpdate => ({ kind: "snapshot", topic: "counter:c1", seq: 3, data, resync });
const event = (type: string, data: Record<string, unknown>): RealtimeUpdate => ({ kind: "event", topic: "counter:c1", seq: 4, type, occurredAt: STAMP, data });

describe("topic names (SRS §21.2)", () => {
  it("names the queue of a Service and the counter", () => {
    expect(queueTopic("v1")).toBe("queue:v1");
    expect(counterTopic("c1")).toBe("counter:c1");
  });
});

describe("waitingFrom", () => {
  it("reads the waiting count of a Service from its queue topic, from a snapshot or an event", () => {
    expect(waitingFrom({ kind: "snapshot", topic: "queue:v1", seq: 0, data: { waiting_count: 3 }, resync: false })).toEqual({ serviceId: "v1", count: 3 });
    expect(waitingFrom({ kind: "event", topic: "queue:v2", seq: 1, type: "ticket.issued", occurredAt: STAMP, data: { waiting_count: 0 } })).toEqual({ serviceId: "v2", count: 0 });
  });

  it("says nothing for other topics, refusals and updates without a count", () => {
    expect(waitingFrom({ kind: "snapshot", topic: "counter:c1", seq: 0, data: { waiting_count: 3 }, resync: false })).toBeNull();
    expect(waitingFrom({ kind: "denied", topic: "queue:v1", code: "forbidden" })).toBeNull();
    expect(waitingFrom({ kind: "event", topic: "queue:v1", seq: 1, type: "ticket.serving", occurredAt: STAMP, data: {} })).toBeNull();
  });
});

describe("counterMovedOn: is the console behind the server?", () => {
  it("is not behind when a snapshot shows the session and ticket it already shows", () => {
    const shown = session({ ticket: ticket() });
    expect(counterMovedOn(snapshot({ session: { id: "s1", state: "open" }, ticket: { id: "t1", state: "called", version: 1 } }), shown)).toBe(false);
    expect(counterMovedOn(snapshot({ session: { id: "s1", state: "open" }, ticket: null }), session())).toBe(false);
  });

  it("is behind when a snapshot shows another ticket, state, version or session state", () => {
    const shown = session({ ticket: ticket() });
    const live = { id: "s1", state: "open" };
    expect(counterMovedOn(snapshot({ session: live, ticket: null }), shown)).toBe(true);
    expect(counterMovedOn(snapshot({ session: live, ticket: { id: "t2", state: "called", version: 1 } }), shown)).toBe(true);
    expect(counterMovedOn(snapshot({ session: live, ticket: { id: "t1", state: "serving", version: 2 } }), shown)).toBe(true);
    expect(counterMovedOn(snapshot({ session: live, ticket: { id: "t1", state: "called", version: 3 } }), shown)).toBe(true);
    expect(counterMovedOn(snapshot({ session: { id: "s1", state: "closing" }, ticket: { id: "t1", state: "called", version: 1 } }), shown)).toBe(true);
    expect(counterMovedOn(snapshot({ session: null, ticket: null }), shown)).toBe(true);
    expect(counterMovedOn(snapshot({ session: { id: "other", state: "open" }, ticket: null }), session())).toBe(true);
  });

  it("is always behind after a resync, since the hub could not say what was missed", () => {
    expect(counterMovedOn(snapshot({ session: { id: "s1", state: "open" }, ticket: null }, true), session())).toBe(true);
  });

  it("takes an event that shows what the screen shows for the echo of the agent's own action", () => {
    expect(counterMovedOn(event("ticket.called", { ticket_id: "t1", state: "called" }), session({ ticket: ticket() }))).toBe(false);
    expect(counterMovedOn(event("ticket.serving", { ticket_id: "t1", state: "serving" }), session({ ticket: ticket({ state: "serving" }) }))).toBe(false);
    expect(counterMovedOn(event("ticket.completed", { ticket_id: "t1", state: "completed" }), session())).toBe(false);
  });

  it("is behind when a Re-announce made elsewhere moved the announce count, and not when the screen already shows it", () => {
    const shown = session({ ticket: ticket({ announce_count: 1 }) });
    expect(counterMovedOn(event("ticket.reannounced", { ticket_id: "t1", state: "called", announce_count: 2 }), shown)).toBe(true);
    expect(counterMovedOn(event("ticket.reannounced", { ticket_id: "t1", state: "called", announce_count: 1 }), shown)).toBe(false);
  });

  it("is behind when the ticket it shows was missed, since it has gone back to the queue", () => {
    expect(counterMovedOn(event("ticket.missed", { ticket_id: "t1", state: "waiting" }), session({ ticket: ticket() }))).toBe(true);
    expect(counterMovedOn(event("ticket.missed", { ticket_id: "t1", state: "waiting" }), session())).toBe(false);
  });

  it("is behind on an event the screen does not yet show", () => {
    expect(counterMovedOn(event("ticket.called", { ticket_id: "t2", state: "called" }), session())).toBe(true);
    expect(counterMovedOn(event("ticket.serving", { ticket_id: "t1", state: "serving" }), session({ ticket: ticket() }))).toBe(true);
    expect(counterMovedOn(event("ticket.completed", { ticket_id: "t1", state: "completed" }), session({ ticket: ticket({ state: "serving" }) }))).toBe(true);
    expect(counterMovedOn(event("ticket.no_show", { ticket_id: "t1", state: "no_show" }), session({ ticket: ticket() }))).toBe(true);
  });

  it("ignores an event about a ticket the screen is not holding when that ticket has left", () => {
    expect(counterMovedOn(event("ticket.completed", { ticket_id: "t9", state: "completed" }), session({ ticket: ticket() }))).toBe(false);
  });

  it("is behind when a ticket was held or resumed elsewhere, so the held list is stale (FR-AGT-013)", () => {
    const parked = ticket({ state: "held", version: 3 });
    expect(counterMovedOn(event("ticket.held", { ticket_id: "t1", state: "held" }), session({ ticket: ticket({ state: "serving" }) }))).toBe(true);
    expect(counterMovedOn(event("ticket.held", { ticket_id: "t1", state: "held" }), session())).toBe(true);
    expect(counterMovedOn(event("ticket.held", { ticket_id: "t1", state: "held" }), session({ held: [parked] }))).toBe(false);
    expect(counterMovedOn(event("ticket.serving", { ticket_id: "t1", state: "serving" }), session({ held: [parked] }))).toBe(true);
    expect(counterMovedOn(event("ticket.position_changed", { ticket_id: "t1", state: "waiting" }), session({ held: [parked] }))).toBe(true);
    expect(counterMovedOn(event("ticket.position_changed", { ticket_id: "t9", state: "waiting" }), session({ held: [parked] }))).toBe(false);
  });

  it("follows the session's own opening and closing", () => {
    expect(counterMovedOn(event("session.closed", { session_id: "s1" }), session())).toBe(true);
    expect(counterMovedOn(event("session.closed", { session_id: "older" }), session())).toBe(false);
    expect(counterMovedOn(event("session.opened", { session_id: "s1" }), session())).toBe(false);
    expect(counterMovedOn(event("session.opened", { session_id: "s2" }), session())).toBe(true);
  });

  it("is not moved by a refusal", () => {
    expect(counterMovedOn({ kind: "denied", topic: "counter:c1", code: "forbidden" }, session())).toBe(false);
  });
});
