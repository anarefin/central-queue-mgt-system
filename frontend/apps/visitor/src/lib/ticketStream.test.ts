import type { ApiClient, VisitorTicketView } from "@qms/api-client";
import { ApiRequestError } from "@qms/api-client";
import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useTicketStream, type TicketStreamDeps } from "./ticketStream";

const VIEW: VisitorTicketView = {
  ticket_id: "t1",
  token_number: "A-001",
  state: "waiting",
  service_id: "v1",
  position: 3,
  estimated_wait_minutes: { low: 10, high: 15 },
  now_serving_token_number: null,
  zone: null,
  updated_at: "2026-09-20T10:00:00Z",
};

class FakeSocket {
  static instances: FakeSocket[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  sent: string[] = [];
  closed = false;

  constructor(
    public url: string,
    public protocols: string[],
  ) {
    FakeSocket.instances.push(this);
  }

  send(data: string) {
    this.sent.push(data);
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    this.onclose?.();
  }
}

function fakeClient(visitorView: (id: string, credential: string) => Promise<VisitorTicketView>): ApiClient {
  return { tickets: { visitorView } } as unknown as ApiClient;
}

/** A manually-driven interval, so a test advances polling by calling `tick()` instead of faking real timers (which
 * does not mix well with React's own `act` scheduling). */
function manualInterval() {
  let callback: (() => void) | null = null;
  const setIntervalImpl = ((cb: () => void) => {
    callback = cb;
    return 1 as unknown as ReturnType<typeof setInterval>;
  }) as typeof setInterval;
  const clearIntervalImpl = (() => {
    callback = null;
  }) as typeof clearInterval;
  return { setInterval: setIntervalImpl, clearInterval: clearIntervalImpl, tick: () => callback?.() };
}

afterEach(() => {
  FakeSocket.instances = [];
  vi.restoreAllMocks();
});

describe("useTicketStream", () => {
  it("subscribes to the ticket's own topic and applies the snapshot as live", async () => {
    const visitorView = vi.fn();
    const client = fakeClient(visitorView);
    const deps: TicketStreamDeps = { createSocket: (url, protocols) => new FakeSocket(url, protocols) as unknown as WebSocket };

    const { result } = renderHook(() => useTicketStream(client, "https://api.example", "t1", "s3cr3t", deps));

    expect(FakeSocket.instances).toHaveLength(1);
    const socket = FakeSocket.instances[0]!;
    expect(socket.url).toBe("wss://api.example/api/v1/stream");
    expect(socket.protocols).toEqual(["qms.v1", "ticket.t1.s3cr3t"]);

    act(() => socket.onopen?.());
    expect(JSON.parse(socket.sent[0]!)).toEqual({ frame: "subscribe", topics: ["ticket:t1"] });

    act(() => socket.onmessage?.({ data: JSON.stringify({ frame: "snapshot", topic: "ticket:t1", seq: 1, epoch: "e1", data: VIEW }) }));

    expect(result.current.view).toEqual(VIEW);
    expect(result.current.live).toBe(true);
    expect(result.current.lastUpdatedAt).not.toBeNull();
    expect(visitorView).not.toHaveBeenCalled();
  });

  it("re-reads over REST when a live event arrives, so derived fields like now-serving stay accurate", async () => {
    const visitorView = vi.fn().mockResolvedValue({ ...VIEW, position: 2 });
    const client = fakeClient(visitorView);
    const deps: TicketStreamDeps = { createSocket: (url, protocols) => new FakeSocket(url, protocols) as unknown as WebSocket };

    renderHook(() => useTicketStream(client, "", "t1", "s3cr3t", deps));
    const socket = FakeSocket.instances[0]!;
    act(() => socket.onopen?.());
    act(() => socket.onmessage?.({ data: JSON.stringify({ frame: "event", topic: "ticket:t1", seq: 2, type: "ticket.position_changed", data: {} }) }));

    await waitFor(() => expect(visitorView).toHaveBeenCalledWith("t1", "s3cr3t"));
  });

  it("falls back to polling when the socket cannot be created, and stays live=false", async () => {
    const visitorView = vi.fn().mockResolvedValue(VIEW);
    const client = fakeClient(visitorView);
    const clock = manualInterval();
    const deps: TicketStreamDeps = {
      createSocket: () => {
        throw new Error("WebSocket is not available");
      },
      setInterval: clock.setInterval,
      clearInterval: clock.clearInterval,
    };

    const { result } = renderHook(() => useTicketStream(client, "", "t1", "s3cr3t", deps));

    await waitFor(() => expect(result.current.view).toEqual(VIEW));
    expect(result.current.live).toBe(false);
    expect(visitorView).toHaveBeenCalledTimes(1);

    await act(async () => {
      clock.tick();
      await Promise.resolve();
    });
    expect(visitorView).toHaveBeenCalledTimes(2);
  });

  it("stops polling once the credential is unauthenticated, since retrying never helps (FR-SEC-033)", async () => {
    const visitorView = vi.fn().mockRejectedValue(new ApiRequestError(401, "unauthenticated", "no"));
    const client = fakeClient(visitorView);
    const clock = manualInterval();
    const deps: TicketStreamDeps = {
      createSocket: () => {
        throw new Error("WebSocket is not available");
      },
      setInterval: clock.setInterval,
      clearInterval: clock.clearInterval,
    };

    const { result } = renderHook(() => useTicketStream(client, "", "t1", "wrong-secret", deps));

    await waitFor(() => expect(result.current.errorCode).toBe("unauthenticated"));
    expect(visitorView).toHaveBeenCalledTimes(1);

    // No further attempts: a wrong secret does not become right by retrying. The interval was cleared, so a manual
    // tick (which a real interval could never produce once cleared) proves it, rather than just not calling it.
    await act(async () => {
      clock.tick();
      await Promise.resolve();
    });
    expect(visitorView).toHaveBeenCalledTimes(1);
  });

  it("falls back to polling once the socket closes after opening (WS blocked mid-session)", async () => {
    const visitorView = vi.fn().mockResolvedValue(VIEW);
    const client = fakeClient(visitorView);
    const clock = manualInterval();
    const deps: TicketStreamDeps = {
      createSocket: (url, protocols) => new FakeSocket(url, protocols) as unknown as WebSocket,
      setInterval: clock.setInterval,
      clearInterval: clock.clearInterval,
    };

    renderHook(() => useTicketStream(client, "", "t1", "s3cr3t", deps));
    const socket = FakeSocket.instances[0]!;
    act(() => socket.onopen?.());
    expect(visitorView).not.toHaveBeenCalled();

    act(() => socket.close());
    await waitFor(() => expect(visitorView).toHaveBeenCalledTimes(1));
  });
});
