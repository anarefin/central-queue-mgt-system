import type { DisplayState } from "@qms/api-client";
import { I18nProvider } from "@qms/i18n/react";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { useEffect, useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Speaker } from "../lib/announcementQueue";
import { useApi, RuntimeProvider } from "../lib/runtime";
import { NowServingBoard } from "./NowServingBoard";

/** A speaker the test can inspect, standing in for the browser's real TTS/clip speaker (ticket 29). */
function fakeSpeaker() {
  const chimes: Array<{ chime: string; volume: number }> = [];
  const spoken: Array<{ text: string; language: string }> = [];
  const speaker: Speaker = {
    playChime: async (chime, volumePercent) => {
      chimes.push({ chime, volume: volumePercent });
    },
    speak: async (text, language) => {
      spoken.push({ text, language });
    },
  };
  return { speaker, chimes, spoken };
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

type Route = (init: RequestInit) => Response | Promise<Response>;

/** A WebSocket the test drives by hand: open it, then push snapshot/event frames, the same shape the hub sends. */
class FakeWebSocket {
  static all: FakeWebSocket[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onclose: ((event: { code?: number }) => void) | null = null;
  onerror: (() => void) | null = null;
  sent: Array<Record<string, unknown>> = [];

  constructor() {
    FakeWebSocket.all.push(this);
  }

  send(data: string) {
    this.sent.push(JSON.parse(data) as Record<string, unknown>);
  }

  close() {}

  open() {
    this.onopen?.();
  }

  say(frame: Record<string, unknown>) {
    this.onmessage?.({ data: JSON.stringify(frame) });
  }

  static last(): FakeWebSocket {
    return FakeWebSocket.all[FakeWebSocket.all.length - 1] as FakeWebSocket;
  }
}

function stubApi(routes: Record<string, Route>) {
  vi.stubGlobal("fetch", async (url: string, init: RequestInit = {}) => {
    if (url.endsWith("/config.json")) return json(200, { apiOrigin: "" });
    const path = url.replace(/^.*\/api\/v1/, "");
    const method = init.method ?? "GET";
    const route = routes[`${method} ${path}`];
    if (!route) throw new TypeError(`unrouted ${method} ${path}`);
    return route(init);
  });
  vi.stubGlobal("WebSocket", FakeWebSocket as unknown as typeof WebSocket);
}

/** The board is only ever mounted once a display is paired (see `DevicePairing`); this pairs it first, for real, so
 * the realtime client has an access token to open a socket with. */
function Harness({ deviceId, speaker }: { deviceId: string; speaker?: Speaker }) {
  const { session } = useApi();
  const [ready, setReady] = useState(false);
  useEffect(() => {
    if (!session || ready) return;
    void session.pair("CODE1234").then(() => setReady(true));
  }, [session, ready]);
  return ready ? <NowServingBoard deviceId={deviceId} speaker={speaker} /> : null;
}

function renderBoard(speaker?: Speaker) {
  return render(
    <I18nProvider loadExtra={false}>
      <RuntimeProvider>
        <Harness deviceId="d1" speaker={speaker} />
      </RuntimeProvider>
    </I18nProvider>,
  );
}

const PAIR_RESPONSE = {
  device_id: "d1",
  kind: "display",
  site_id: "s1",
  zone_id: "z1",
  access_token: "access-1",
  token_type: "Bearer",
  expires_in: 900,
  refresh_token: "refresh-1",
};

const STATE: DisplayState = {
  zone: {
    id: "z1",
    name: "Ground waiting",
    building_label: null,
    floor_label: "Ground",
    chime: "chime_standard",
    chime_volume: 80,
    quiet_start: null,
    quiet_end: null,
    announcement_languages: ["en"],
    max_announce_queue_depth: 5,
  },
  layout: "now_serving_table",
  language_cycle: ["en"],
  columns: ["token", "counter", "service"],
  next_n: 4,
  highlight_seconds: 10,
  assignment: { scope: "zone", ids: [] },
  serving: [
    {
      counter_id: "c1",
      counter_label: "Desk 1",
      token_number: "A-001",
      state: "called",
      service_id: "s1",
      service_names: { en: "Consultation" },
      staff_name: "Dr. Karim",
      token_prefix: "A",
      token_prefix_spoken: { en: "A" },
      announce_visitor_name: false,
    },
  ],
  next: [{ service_id: "s1", service_names: { en: "Consultation" }, tokens: [{ token_number: "A-002", position: 1 }] }],
};

beforeEach(() => {
  FakeWebSocket.all = [];
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("now-serving board (ticket 28)", () => {
  it("shows who is being served where, with the configured columns, and the next-token strip (FR-DSP-004, FR-DSP-005)", async () => {
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () => json(200, STATE),
    });
    renderBoard();

    expect(await screen.findByText("A-001")).toBeInTheDocument();
    const table = screen.getByRole("table");
    expect(within(table).getByText("Desk 1")).toBeInTheDocument();
    expect(within(table).getByText("Consultation")).toBeInTheDocument();
    expect(within(table).queryByText("Dr. Karim")).not.toBeInTheDocument(); // "staff" is not in this display's configured columns
    expect(screen.getByText("A-002")).toBeInTheDocument();
  });

  it("highlights a newly called token pushed live on the zone topic (FR-DSP-007, FR-DSP-010)", async () => {
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () => json(200, STATE),
    });
    renderBoard();
    await screen.findByText("A-001");

    await act(async () => {
      FakeWebSocket.last().open();
    });
    const row = () => screen.getByText("Desk 1").closest("tr") as HTMLElement;
    expect(row()).not.toHaveClass("qms-now-serving-row--highlight");

    // The hub replies to the client's `subscribe` frame with a snapshot before any delta is meaningful (§21.1);
    // the fake socket stands in for that reply so the event below is not a duplicate ahead of an unknown seq.
    await act(async () => {
      FakeWebSocket.last().say({
        frame: "snapshot",
        topic: "zone:z1",
        seq: 0,
        epoch: "e1",
        resync: false,
        data: { serving: STATE.serving, next: STATE.next },
      });
    });

    await act(async () => {
      FakeWebSocket.last().say({
        frame: "event",
        topic: "zone:z1",
        seq: 1,
        type: "ticket.called",
        occurred_at: "2026-09-20T10:00:00Z",
        data: { counter_id: "c1", token_number: "A-003", state: "called", service_id: "s1" },
      });
    });

    expect(await screen.findByText("A-003")).toBeInTheDocument();
    expect(row()).toHaveClass("qms-now-serving-row--highlight");
  });

  it("shows a discreet stale indicator once nothing has updated for a while, rather than showing wrong data silently (FR-DSP-011)", async () => {
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () => json(200, STATE),
    });
    renderBoard();
    await screen.findByText("A-001");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();

    // Jumps the clock forward instead of waiting 30 real seconds; the component's own 1s tick (unaffected by this)
    // picks the jump up on its very next run.
    vi.spyOn(Date, "now").mockReturnValue(Date.now() + 40_000);
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 1100));
    });

    expect(screen.getByRole("status")).toHaveTextContent("Update delayed");
  }, 10_000);

  it("shows a load error when the display-state read fails", async () => {
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () => json(500, { error: { code: "internal_error", message: "x", trace_id: "t" } }),
    });
    renderBoard();

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Could not load the now-serving board."));
  });
});

describe("voice announcements (ticket 29)", () => {
  async function openAndSnapshot(state: typeof STATE) {
    await act(async () => {
      FakeWebSocket.last().open();
    });
    await act(async () => {
      FakeWebSocket.last().say({
        frame: "snapshot",
        topic: "zone:z1",
        seq: 0,
        epoch: "e1",
        resync: false,
        data: { serving: state.serving, next: state.next },
      });
    });
  }

  function callEvent(seq: number, overrides: Record<string, unknown> = {}) {
    return {
      frame: "event",
      topic: "zone:z1",
      seq,
      type: "ticket.called",
      occurred_at: "2026-09-20T10:00:00Z",
      data: { ticket_id: "t1", counter_id: "c1", token_number: "A-003", state: "called", service_id: "s1", announce_count: 0, ...overrides },
    };
  }

  it("plays a chime then speaks the call in each configured language, in order (FR-DSP-020, FR-DSP-023, FR-DSP-025)", async () => {
    const { speaker, chimes, spoken } = fakeSpeaker();
    const state = { ...STATE, zone: { ...STATE.zone, announcement_languages: ["bn", "en"] } };
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () => json(200, state),
    });
    renderBoard(speaker);
    await screen.findByText("A-001");
    await openAndSnapshot(state);

    await act(async () => {
      FakeWebSocket.last().say(callEvent(1));
    });

    await waitFor(() => expect(chimes).toHaveLength(1));
    expect(chimes[0]).toEqual({ chime: "chime_standard", volume: 80 });
    await waitFor(() => expect(spoken).toHaveLength(2));
    expect(spoken.map((s) => s.language)).toEqual(["bn", "en"]); // FR-DSP-023: configured order
    expect(spoken[1]!.text).toContain("zero zero three");
  });

  it("stays silent for a snapshot alone -- only an actual call event triggers audio", async () => {
    const { speaker, chimes, spoken } = fakeSpeaker();
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () => json(200, STATE),
    });
    renderBoard(speaker);
    await screen.findByText("A-001");
    await openAndSnapshot(STATE);

    expect(chimes).toHaveLength(0);
    expect(spoken).toHaveLength(0);
  });

  it("never replays a call a resync redelivers, so a reconnect never re-announces an old token (FR-QUE-083)", async () => {
    const { speaker, chimes, spoken } = fakeSpeaker();
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () => json(200, STATE),
    });
    renderBoard(speaker);
    await screen.findByText("A-001");
    await openAndSnapshot(STATE);

    await act(async () => {
      FakeWebSocket.last().say(callEvent(1));
    });
    await waitFor(() => expect(chimes).toHaveLength(1));
    await waitFor(() => expect(spoken).toHaveLength(1)); // this zone's default is English only

    // The same ticket_id + announce_count, redelivered (a resync after a reconnect) -- must not play again.
    await act(async () => {
      FakeWebSocket.last().say(callEvent(2));
    });
    expect(chimes).toHaveLength(1);
    expect(spoken).toHaveLength(1);
  });

  it("re-announcing the same ticket (a new announce_count) plays again (FR-DSP-028)", async () => {
    const { speaker, chimes } = fakeSpeaker();
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () => json(200, STATE),
    });
    renderBoard(speaker);
    await screen.findByText("A-001");
    await openAndSnapshot(STATE);

    await act(async () => {
      FakeWebSocket.last().say(callEvent(1, { announce_count: 0 }));
    });
    await waitFor(() => expect(chimes).toHaveLength(1));

    await act(async () => {
      FakeWebSocket.last().say({ ...callEvent(2, { announce_count: 1 }), type: "ticket.reannounced" });
    });
    await waitFor(() => expect(chimes).toHaveLength(2));
  });

  it("suppresses the chime and speech during the zone's configured quiet period, while the display still updates (FR-DSP-027)", async () => {
    vi.setSystemTime(new Date(2026, 8, 20, 23, 0, 0));
    const { speaker, chimes, spoken } = fakeSpeaker();
    const state = { ...STATE, zone: { ...STATE.zone, quiet_start: "22:00", quiet_end: "06:00" } };
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () => json(200, state),
    });
    renderBoard(speaker);
    await screen.findByText("A-001");
    await openAndSnapshot(state);

    await act(async () => {
      FakeWebSocket.last().say(callEvent(1));
    });

    expect(await screen.findByText("A-003")).toBeInTheDocument(); // display still updates
    expect(chimes).toHaveLength(0);
    expect(spoken).toHaveLength(0);
  });
});
