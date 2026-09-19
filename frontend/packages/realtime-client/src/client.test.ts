import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createRealtimeClient, streamUrl, type RealtimeClientOptions, type RealtimeUpdate, type SocketLike, type TopicSnapshot } from "./index";

const QUEUE = "queue:v1";
const COUNTER = "counter:c1";

/** A WebSocket the test drives by hand. */
class FakeSocket implements SocketLike {
  static all: FakeSocket[] = [];
  sent: Array<Record<string, unknown>> = [];
  closed = false;
  onopen: SocketLike["onopen"] = null;
  onmessage: SocketLike["onmessage"] = null;
  onclose: SocketLike["onclose"] = null;
  onerror: SocketLike["onerror"] = null;

  constructor(
    readonly url: string,
    readonly protocols: string[],
  ) {
    FakeSocket.all.push(this);
  }

  send(data: string): void {
    this.sent.push(JSON.parse(data) as Record<string, unknown>);
  }

  close(): void {
    this.closed = true;
  }

  // What the network does.
  open(): void {
    this.onopen?.({});
  }

  say(frame: Record<string, unknown>): void {
    this.onmessage?.({ data: JSON.stringify(frame) });
  }

  drop(): void {
    this.onclose?.({});
  }

  fail(): void {
    this.onerror?.({});
    this.onclose?.({});
  }

  frames(kind: string): Array<Record<string, unknown>> {
    return this.sent.filter((f) => f.frame === kind);
  }
}

const socketAt = (n: number): FakeSocket => FakeSocket.all[n] as FakeSocket;
const lastSocket = (): FakeSocket => FakeSocket.all[FakeSocket.all.length - 1] as FakeSocket;

function snapshot(topic: string, seq: number, data: Record<string, unknown> = {}, extra: Record<string, unknown> = {}) {
  return { frame: "snapshot", topic, seq, epoch: "e1", resync: false, data, ...extra };
}

function event(topic: string, seq: number, data: Record<string, unknown> = {}, type = "ticket.issued") {
  return { frame: "event", topic, seq, type, occurred_at: "2026-09-19T10:00:00Z", data };
}

function setup(over: Partial<RealtimeClientOptions> = {}) {
  let token: string | null = "tok-1";
  const client = createRealtimeClient({
    url: "ws://qms.test/api/v1/stream",
    getAccessToken: () => token,
    createSocket: (url, protocols) => new FakeSocket(url, protocols),
    ...over,
  });
  return { client, setToken: (next: string | null) => (token = next) };
}

function listen(client: ReturnType<typeof createRealtimeClient>, topic = QUEUE) {
  const updates: RealtimeUpdate[] = [];
  const stop = client.subscribe(topic, (u) => updates.push(u));
  return { updates, stop };
}

beforeEach(() => {
  vi.useFakeTimers();
  FakeSocket.all = [];
});
afterEach(() => vi.useRealTimers());

describe("connecting and subscribing (SRS §21.1)", () => {
  it("offers the access token in the subprotocol, subscribes once open, and hands over the snapshot and then the deltas", () => {
    const { client } = setup();
    const { updates } = listen(client);

    expect(lastSocket().url).toBe("ws://qms.test/api/v1/stream");
    expect(lastSocket().protocols).toEqual(["qms.v1", "bearer.tok-1"]);
    expect(lastSocket().sent).toEqual([]); // nothing goes out before the socket is open
    lastSocket().open();
    expect(lastSocket().frames("subscribe")).toEqual([{ frame: "subscribe", topics: [{ topic: QUEUE }] }]);

    lastSocket().say(snapshot(QUEUE, 0, { waiting_count: 3 }));
    lastSocket().say(event(QUEUE, 1, { waiting_count: 4 }));
    lastSocket().say(event(QUEUE, 2, { waiting_count: 3 }, "ticket.called"));

    expect(updates).toEqual([
      { kind: "snapshot", topic: QUEUE, seq: 0, data: { waiting_count: 3 }, resync: false },
      { kind: "event", topic: QUEUE, seq: 1, type: "ticket.issued", occurredAt: "2026-09-19T10:00:00Z", data: { waiting_count: 4 } },
      { kind: "event", topic: QUEUE, seq: 2, type: "ticket.called", occurredAt: "2026-09-19T10:00:00Z", data: { waiting_count: 3 } },
    ]);
    expect(client.diagnostics()).toMatchObject({ transport: "websocket", degraded: false });
  });

  it("uses one socket for every topic and keeps each topic's events to its own listeners", () => {
    const { client } = setup();
    const onQueue = listen(client, QUEUE);
    const onCounter = listen(client, COUNTER);
    expect(FakeSocket.all).toHaveLength(1);
    lastSocket().open();
    expect(lastSocket().frames("subscribe")).toEqual([{ frame: "subscribe", topics: [{ topic: QUEUE }, { topic: COUNTER }] }]);

    lastSocket().say(snapshot(QUEUE, 0));
    lastSocket().say(snapshot(COUNTER, 0));
    lastSocket().say(event(COUNTER, 1));

    expect(onQueue.updates.map((u) => u.kind)).toEqual(["snapshot"]);
    expect(onCounter.updates.map((u) => u.kind)).toEqual(["snapshot", "event"]);
  });

  it("subscribes at once to a topic added while the socket is open, and asks for nothing until the socket is open", () => {
    const { client } = setup();
    listen(client, QUEUE);
    listen(client, COUNTER);
    expect(lastSocket().sent).toEqual([]);
    lastSocket().open();
    listen(client, "queue:v2");
    expect(lastSocket().frames("subscribe").at(-1)).toEqual({ frame: "subscribe", topics: [{ topic: "queue:v2" }] });
  });

  it("tells a listener when the hub refuses its topic", () => {
    const { client } = setup();
    const { updates } = listen(client);
    lastSocket().open();
    lastSocket().say({ frame: "denied", topic: QUEUE, code: "forbidden" });

    expect(updates).toEqual([{ kind: "denied", topic: QUEUE, code: "forbidden" }]);
  });

  it("stops listening on unsubscribe, tells the hub, and closes the socket when nobody listens to anything", () => {
    const { client } = setup();
    const a = listen(client, QUEUE);
    const b = listen(client, COUNTER);
    lastSocket().open();
    lastSocket().say(snapshot(QUEUE, 0));

    a.stop();
    expect(lastSocket().frames("unsubscribe")).toEqual([{ frame: "unsubscribe", topics: [QUEUE] }]);
    lastSocket().say(event(QUEUE, 1));
    expect(a.updates.map((u) => u.kind)).toEqual(["snapshot"]);

    const socket = lastSocket();
    b.stop();
    expect(socket.closed).toBe(true);
    expect(client.diagnostics().transport).toBe("idle");
    vi.advanceTimersByTime(120_000);
    expect(FakeSocket.all).toHaveLength(1); // and it does not dial again
  });

  it("gives a second listener on a topic a fresh snapshot without disturbing the first", () => {
    const { client } = setup();
    const first = listen(client);
    lastSocket().open();
    lastSocket().say(snapshot(QUEUE, 5, { waiting_count: 2 }));
    const second = listen(client);

    expect(lastSocket().frames("subscribe").at(-1)).toEqual({ frame: "subscribe", topics: [{ topic: QUEUE }] });
    lastSocket().say(snapshot(QUEUE, 5, { waiting_count: 2 }));

    expect(second.updates.map((u) => u.kind)).toEqual(["snapshot"]);
    expect(first.updates.map((u) => u.kind)).toEqual(["snapshot", "snapshot"]);
  });
});

describe("applying events idempotently by seq (FR-QUE-082)", () => {
  it("applies an event once however many times it arrives, and ignores an older one", () => {
    const { client } = setup();
    const { updates } = listen(client);
    lastSocket().open();
    lastSocket().say(snapshot(QUEUE, 10));
    lastSocket().say(event(QUEUE, 11, { n: 1 }));
    lastSocket().say(event(QUEUE, 11, { n: 1 }));
    lastSocket().say(event(QUEUE, 12, { n: 2 }));
    lastSocket().say(event(QUEUE, 11, { n: 1 }));
    lastSocket().say(event(QUEUE, 9, { n: 0 }));

    expect(updates.filter((u) => u.kind === "event").map((u) => u.kind === "event" && u.seq)).toEqual([11, 12]);
  });

  it("ignores an event at or below the snapshot's seq, as the hub may deliver one the snapshot already includes", () => {
    const { client } = setup();
    const { updates } = listen(client);
    lastSocket().open();
    lastSocket().say(snapshot(QUEUE, 4));
    lastSocket().say(event(QUEUE, 4));
    lastSocket().say(event(QUEUE, 3));
    lastSocket().say(event(QUEUE, 5));

    expect(updates.map((u) => u.kind === "event" && u.seq)).toEqual([false, 5]);
  });

  it("does not apply an event out of order: it asks to be caught up once, then applies the replay and drops what it already had", () => {
    const { client } = setup();
    const { updates } = listen(client);
    lastSocket().open();
    lastSocket().say(snapshot(QUEUE, 1));
    lastSocket().say(event(QUEUE, 2));
    lastSocket().say(event(QUEUE, 4)); // 3 is missing
    lastSocket().say(event(QUEUE, 5)); // still waiting for the replay: no second request

    expect(lastSocket().frames("subscribe").slice(1)).toEqual([{ frame: "subscribe", topics: [{ topic: QUEUE, last_seq: 2, epoch: "e1" }] }]);
    expect(updates.filter((u) => u.kind === "event")).toHaveLength(1);

    lastSocket().say({ frame: "replay", topic: QUEUE, seq: 5, epoch: "e1", count: 3 });
    lastSocket().say(event(QUEUE, 3));
    lastSocket().say(event(QUEUE, 4));
    lastSocket().say(event(QUEUE, 5));

    expect(updates.map((u) => u.kind === "event" && u.seq)).toEqual([false, 2, 3, 4, 5]);
  });

  it("takes a resync snapshot when the hub cannot replay, and carries on from its seq", () => {
    const { client } = setup();
    const { updates } = listen(client);
    lastSocket().open();
    lastSocket().say(snapshot(QUEUE, 1));
    lastSocket().say(event(QUEUE, 5)); // a gap
    lastSocket().say(snapshot(QUEUE, 40, { waiting_count: 9 }, { resync: true }));
    lastSocket().say(event(QUEUE, 41));

    expect(updates.map((u) => u.kind)).toEqual(["snapshot", "snapshot", "event"]);
    expect(updates[1]).toMatchObject({ resync: true, seq: 40, data: { waiting_count: 9 } });
  });

  it("survives a listener that throws", () => {
    const { client } = setup();
    client.subscribe(QUEUE, () => {
      throw new Error("boom");
    });
    const { updates } = listen(client);
    lastSocket().open();
    lastSocket().say(snapshot(QUEUE, 0));
    lastSocket().say(event(QUEUE, 1));

    expect(updates).toHaveLength(2);
  });
});

describe("reconnecting without gaps or duplicates (FR-QUE-081)", () => {
  it("dials again after a drop, reads the token afresh, and sends the last seq it applied with the epoch", () => {
    const { client, setToken } = setup();
    const { updates } = listen(client);
    lastSocket().open();
    lastSocket().say(snapshot(QUEUE, 0));
    lastSocket().say(event(QUEUE, 1));
    lastSocket().say(event(QUEUE, 2));

    setToken("tok-2");
    lastSocket().drop();
    expect(FakeSocket.all).toHaveLength(1);
    vi.advanceTimersByTime(1_000);
    expect(FakeSocket.all).toHaveLength(2);
    expect(lastSocket().protocols).toEqual(["qms.v1", "bearer.tok-2"]);
    lastSocket().open();
    expect(lastSocket().frames("subscribe")).toEqual([{ frame: "subscribe", topics: [{ topic: QUEUE, last_seq: 2, epoch: "e1" }] }]);

    // The hub replays what was missed, starting with events the client already has because the drop raced them.
    lastSocket().say({ frame: "replay", topic: QUEUE, seq: 4, epoch: "e1", count: 3 });
    lastSocket().say(event(QUEUE, 2));
    lastSocket().say(event(QUEUE, 3));
    lastSocket().say(event(QUEUE, 4));

    expect(updates.map((u) => u.kind === "event" && u.seq)).toEqual([false, 1, 2, 3, 4]);
    expect(client.diagnostics()).toMatchObject({ transport: "websocket", reconnects: 1, degraded: false });
  });

  it("subscribes fresh, with no seq, to a topic it never got a snapshot for", () => {
    const { client } = setup();
    listen(client);
    lastSocket().open();
    lastSocket().drop();
    vi.advanceTimersByTime(1_000);
    lastSocket().open();

    expect(lastSocket().frames("subscribe")).toEqual([{ frame: "subscribe", topics: [{ topic: QUEUE }] }]);
  });

  it("waits longer after each failed attempt, up to a ceiling", () => {
    const { client } = setup({ fallbackAfterFailures: 99, reconnectDelayMs: 1_000, maxReconnectDelayMs: 4_000 });
    listen(client);
    lastSocket().fail(); // 1st failure: dial again in 1 s
    vi.advanceTimersByTime(999);
    expect(FakeSocket.all).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(FakeSocket.all).toHaveLength(2);
    lastSocket().fail(); // 2nd: 2 s
    vi.advanceTimersByTime(1_999);
    expect(FakeSocket.all).toHaveLength(2);
    vi.advanceTimersByTime(1);
    lastSocket().fail(); // 3rd: 4 s, the ceiling
    vi.advanceTimersByTime(4_000);
    expect(FakeSocket.all).toHaveLength(4);
    lastSocket().fail(); // 4th: still 4 s
    vi.advanceTimersByTime(3_999);
    expect(FakeSocket.all).toHaveLength(4);
    vi.advanceTimersByTime(1);
    expect(FakeSocket.all).toHaveLength(5);
  });

  it("does not try to connect, or fall back to polling, while there is no access token", () => {
    const fetchSnapshot = vi.fn();
    const { client, setToken } = setup({ fetchSnapshot });
    setToken(null);
    listen(client);
    vi.advanceTimersByTime(60_000);

    expect(FakeSocket.all).toHaveLength(0);
    expect(fetchSnapshot).not.toHaveBeenCalled();
    expect(client.diagnostics()).toMatchObject({ degraded: false });

    setToken("tok-1");
    vi.advanceTimersByTime(30_000);
    expect(FakeSocket.all).toHaveLength(1);
  });
});

describe("heartbeat each way (SRS §21.1)", () => {
  it("sends a heartbeat every 20 seconds", () => {
    const { client } = setup();
    listen(client);
    lastSocket().open();
    vi.advanceTimersByTime(20_000);
    expect(lastSocket().frames("heartbeat")).toHaveLength(1);
    lastSocket().say({ frame: "heartbeat", time: "x" });
    vi.advanceTimersByTime(40_000);
    expect(lastSocket().frames("heartbeat")).toHaveLength(3);
  });

  it("reconnects after two missed heartbeats from the hub, and not before", () => {
    const { client } = setup();
    listen(client);
    lastSocket().open();
    vi.advanceTimersByTime(40_000); // two heartbeats missed, not yet more than two
    expect(FakeSocket.all).toHaveLength(1);
    expect(client.diagnostics().transport).toBe("websocket");

    vi.advanceTimersByTime(10_000); // silent for more than two intervals
    expect(socketAt(0).closed).toBe(true);
    expect(client.diagnostics().reconnects).toBe(1);
    vi.advanceTimersByTime(1_000);
    expect(FakeSocket.all).toHaveLength(2);
  });

  it("counts any frame from the hub as it being alive", () => {
    const { client } = setup();
    listen(client);
    lastSocket().open();
    for (let i = 0; i < 6; i++) {
      vi.advanceTimersByTime(30_000);
      lastSocket().say({ frame: "heartbeat", time: "x" });
    }
    expect(FakeSocket.all).toHaveLength(1);
    expect(socketAt(0).closed).toBe(false);
  });

  it("honours a different heartbeat interval", () => {
    const { client } = setup({ heartbeatIntervalMs: 1_000, missedHeartbeats: 3 });
    listen(client);
    lastSocket().open();
    vi.advanceTimersByTime(3_000);
    expect(FakeSocket.all).toHaveLength(1);
    vi.advanceTimersByTime(1_000);
    expect(socketAt(0).closed).toBe(true);
  });
});

describe("falling back to polling where WebSocket is blocked (FR-QUE-084)", () => {
  function snap(topic: string, seq: number, data: Record<string, unknown> = {}): TopicSnapshot {
    return { topic, seq, epoch: "e1", data };
  }

  it("polls for snapshots at the configured interval once the socket has failed twice, and flags degraded mode in diagnostics only", async () => {
    let seq = 1;
    const fetchSnapshot = vi.fn(async (topic: string) => snap(topic, seq, { waiting_count: seq }));
    const { client } = setup({ fetchSnapshot, pollIntervalMs: 15_000 });
    const { updates } = listen(client);
    lastSocket().fail();
    expect(client.diagnostics()).toMatchObject({ transport: "connecting", degraded: false });
    vi.advanceTimersByTime(1_000);
    lastSocket().fail();

    expect(client.diagnostics()).toMatchObject({ transport: "polling", degraded: true });
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchSnapshot).toHaveBeenCalledTimes(1);
    expect(fetchSnapshot).toHaveBeenCalledWith(QUEUE);
    expect(updates).toEqual([{ kind: "snapshot", topic: QUEUE, seq: 1, data: { waiting_count: 1 }, resync: false }]);

    await vi.advanceTimersByTimeAsync(14_999);
    expect(fetchSnapshot).toHaveBeenCalledTimes(1);
    seq = 3;
    await vi.advanceTimersByTimeAsync(1);
    expect(fetchSnapshot).toHaveBeenCalledTimes(2);
    expect(updates.at(-1)).toMatchObject({ kind: "snapshot", seq: 3, data: { waiting_count: 3 } });
  });

  it("defaults to polling every five seconds, and applies a snapshot that has not moved only once", async () => {
    const fetchSnapshot = vi.fn(async (topic: string) => snap(topic, 7));
    const { client } = setup({ fetchSnapshot });
    const { updates } = listen(client);
    lastSocket().fail();
    vi.advanceTimersByTime(1_000);
    lastSocket().fail();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(10_000);

    expect(fetchSnapshot).toHaveBeenCalledTimes(3);
    expect(updates).toHaveLength(1);
  });

  it("keeps polling through a failed request and records it in diagnostics", async () => {
    let fail = true;
    const fetchSnapshot = vi.fn(async (topic: string) => {
      if (fail) throw new Error("offline");
      return snap(topic, 1);
    });
    const { client } = setup({ fetchSnapshot });
    const { updates } = listen(client);
    lastSocket().fail();
    vi.advanceTimersByTime(1_000);
    lastSocket().fail();
    await vi.advanceTimersByTimeAsync(0);
    expect(client.diagnostics().lastError).toContain("offline");

    fail = false;
    await vi.advanceTimersByTimeAsync(5_000);
    expect(updates).toHaveLength(1);
  });

  it("goes back to the WebSocket when it opens, stops polling, and asks to be caught up from what polling delivered", async () => {
    const fetchSnapshot = vi.fn(async (topic: string) => snap(topic, 7));
    const { client } = setup({ fetchSnapshot });
    const { updates } = listen(client);
    lastSocket().fail();
    vi.advanceTimersByTime(1_000);
    lastSocket().fail();
    await vi.advanceTimersByTimeAsync(0);
    expect(client.diagnostics().degraded).toBe(true);

    await vi.advanceTimersByTimeAsync(2_000); // the client keeps trying the socket while it polls
    lastSocket().open();
    expect(client.diagnostics()).toMatchObject({ transport: "websocket", degraded: false });
    expect(lastSocket().frames("subscribe")).toEqual([{ frame: "subscribe", topics: [{ topic: QUEUE, last_seq: 7, epoch: "e1" }] }]);
    lastSocket().say({ frame: "replay", topic: QUEUE, seq: 8, epoch: "e1", count: 1 });
    lastSocket().say(event(QUEUE, 8));

    const calls = fetchSnapshot.mock.calls.length;
    await vi.advanceTimersByTimeAsync(30_000);
    expect(fetchSnapshot.mock.calls.length).toBe(calls);
    expect(updates.map((u) => u.kind)).toEqual(["snapshot", "event"]);
  });

  it("polls a topic added while polling, and never polls a topic no one listens to", async () => {
    const fetchSnapshot = vi.fn(async (topic: string) => snap(topic, 1));
    const { client } = setup({ fetchSnapshot });
    const a = listen(client, QUEUE);
    lastSocket().fail();
    vi.advanceTimersByTime(1_000);
    lastSocket().fail();
    await vi.advanceTimersByTimeAsync(0);
    listen(client, COUNTER);
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchSnapshot).toHaveBeenCalledWith(COUNTER);

    a.stop();
    fetchSnapshot.mockClear();
    await vi.advanceTimersByTimeAsync(5_000);
    expect(fetchSnapshot.mock.calls.map((c) => c[0])).toEqual([COUNTER]);
  });

  it("cannot poll without a way to fetch snapshots, and just keeps dialling", () => {
    const { client } = setup();
    listen(client);
    lastSocket().fail();
    vi.advanceTimersByTime(1_000);
    lastSocket().fail();

    expect(client.diagnostics()).toMatchObject({ transport: "connecting", degraded: false });
    vi.advanceTimersByTime(2_000);
    expect(FakeSocket.all).toHaveLength(3);
  });
});

describe("streamUrl", () => {
  it("turns the API origin into the hub's WebSocket URL", () => {
    expect(streamUrl("https://qms.example.org", "https://ignored")).toBe("wss://qms.example.org/api/v1/stream");
    expect(streamUrl("http://localhost:8080/", "https://ignored")).toBe("ws://localhost:8080/api/v1/stream");
  });

  it("uses the page's own origin when the API origin is empty", () => {
    expect(streamUrl("", "https://qms.example.org")).toBe("wss://qms.example.org/api/v1/stream");
    expect(streamUrl("", "http://localhost:3000")).toBe("ws://localhost:3000/api/v1/stream");
  });
});
