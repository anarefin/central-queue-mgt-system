import type { DisplayState } from "@qms/api-client";
import { I18nProvider } from "@qms/i18n/react";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { useEffect, useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useApi, RuntimeProvider } from "../lib/runtime";
import { DisplayBoard } from "./DisplayBoard";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

type Route = (init: RequestInit) => Response | Promise<Response>;

/** A no-op WebSocket stand-in: these tests only exercise the initial `display-state` read, not live updates. */
class FakeWebSocket {
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onclose: ((event: { code?: number }) => void) | null = null;
  onerror: (() => void) | null = null;
  send() {}
  close() {}
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

function Harness({ deviceId }: { deviceId: string }) {
  const { session } = useApi();
  const [ready, setReady] = useState(false);
  useEffect(() => {
    if (!session || ready) return;
    void session.pair("CODE1234").then(() => setReady(true));
  }, [session, ready]);
  return ready ? <DisplayBoard deviceId={deviceId} /> : null;
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

function baseState(overrides: Partial<DisplayState> = {}): DisplayState {
  return {
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
    layout_config: {},
    language_cycle: ["en"],
    language_cycle_seconds: 10,
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
        service_names: { en: "Consultation", bn: "পরামর্শ" },
        staff_name: "Dr. Karim",
        token_prefix: "A",
        token_prefix_spoken: { en: "A" },
        announce_visitor_name: false,
      },
    ],
    next: [{ service_id: "s1", service_names: { en: "Consultation", bn: "পরামর্শ" }, tokens: [{ token_number: "A-002", position: 1 }] }],
    notices: [],
    summary: [],
    ...overrides,
  };
}

beforeEach(() => {
  // no-op
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("DisplayBoard (ticket 30, FR-DSP-003)", () => {
  it("dispatches now_serving_table to the existing board", async () => {
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () => json(200, baseState({ layout: "now_serving_table" })),
    });
    renderBoard();

    expect(await screen.findByText("A-001")).toBeInTheDocument();
    expect(screen.getByRole("table")).toBeInTheDocument();
  });

  it("split_media shows the serving table sized by layout_config.split_percent, plus the notice panel (FR-DSP-006)", async () => {
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () =>
        json(
          200,
          baseState({
            layout: "split_media",
            layout_config: { split_percent: 35 },
            notices: [{ id: "n1", type: "rich_text", content_i18n: { en: "Please wear a mask" }, sort_order: 0 }],
          }),
        ),
    });
    renderBoard();

    expect(await screen.findByText("A-001")).toBeInTheDocument();
    expect(screen.getByText("Please wear a mask")).toBeInTheDocument();
    const panes = document.querySelector(".qms-split-media-panes") as HTMLElement;
    expect(panes.style.getPropertyValue("--qms-split-percent")).toBe("35");
  });

  it("split_media's notice panel shows an empty message with no active notices", async () => {
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () => json(200, baseState({ layout: "split_media", notices: [] })),
    });
    renderBoard();

    expect(await screen.findByText("No notices are scheduled right now.")).toBeInTheDocument();
  });

  it("single_counter shows one large token for the configured counter (FR-DSP-003)", async () => {
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () =>
        json(200, baseState({ layout: "single_counter", layout_config: { counter_id: "c1" } })),
    });
    renderBoard();

    expect(await screen.findByText("A-001")).toBeInTheDocument();
    expect(screen.getByText("Desk 1")).toBeInTheDocument();
  });

  it("summary_board shows per-service waiting counts and estimated waits for the lobby (FR-DSP-003, SRS §10.5)", async () => {
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () =>
        json(
          200,
          baseState({
            layout: "summary_board",
            summary: [
              { service_id: "s1", service_names: { en: "Consultation" }, waiting_count: 7, estimate_low_minutes: 15, estimate_high_minutes: 20 },
            ],
          }),
        ),
    });
    renderBoard();

    expect(await screen.findByText("Consultation")).toBeInTheDocument();
    expect(screen.getByText("7")).toBeInTheDocument();
    expect(screen.getByText("About 15–20 min")).toBeInTheDocument();
  });

  it("renders side by side, in every configured language, when language_cycle_seconds is 0 (FR-I18N-005)", async () => {
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () =>
        json(
          200,
          baseState({
            layout: "summary_board",
            language_cycle: ["en", "bn"],
            language_cycle_seconds: 0,
            summary: [{ service_id: "s1", service_names: { en: "Consultation", bn: "পরামর্শ" }, waiting_count: 2, estimate_low_minutes: 5, estimate_high_minutes: 10 }],
          }),
        ),
    });
    renderBoard();

    expect(await screen.findByText("Consultation / পরামর্শ")).toBeInTheDocument();
  });

  it("shows a load error when the display-state read fails", async () => {
    stubApi({
      "POST /devices/pair": () => json(201, PAIR_RESPONSE),
      "GET /devices/d1/display-state": () => json(500, { error: { code: "internal_error", message: "x", trace_id: "t" } }),
    });
    renderBoard();

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Could not load the now-serving board."));
  });
});
