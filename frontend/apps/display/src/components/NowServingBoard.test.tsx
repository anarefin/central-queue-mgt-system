import { I18nProvider } from "@qms/i18n/react";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { useEffect, useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useApi, RuntimeProvider } from "../lib/runtime";
import { NowServingBoard } from "./NowServingBoard";

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
function Harness({ deviceId }: { deviceId: string }) {
  const { session } = useApi();
  const [ready, setReady] = useState(false);
  useEffect(() => {
    if (!session || ready) return;
    void session.pair("CODE1234").then(() => setReady(true));
  }, [session, ready]);
  return ready ? <NowServingBoard deviceId={deviceId} /> : null;
}

function renderBoard() {
  return render(
    <I18nProvider loadExtra={false}>
      <RuntimeProvider>
        <Harness deviceId="d1" />
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

const STATE = {
  zone: { id: "z1", name: "Ground waiting", building_label: null, floor_label: "Ground" },
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
