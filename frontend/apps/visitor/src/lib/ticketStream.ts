"use client";

import { ApiRequestError, type ApiClient, type VisitorTicketView } from "@qms/api-client";
import { STREAM_PROTOCOL, streamUrl } from "@qms/realtime-client";
import { useEffect, useState } from "react";

/** FR-QUE-084: the mobile polling fallback interval, three times a display's (5 s), since a phone's network is less reliable. */
export const POLL_INTERVAL_MS = 15_000;
/** How long the page waits for the WebSocket to open before falling back to polling. */
const OPEN_TIMEOUT_MS = 5_000;

export interface TicketStreamState {
  view: VisitorTicketView | null;
  /** True only while a live WebSocket subscription is open; false in polling mode or before the first data arrives.
   * FR-MOB-040: the page must never present a polled or stale figure as if it were live. */
  live: boolean;
  lastUpdatedAt: Date | null;
  /** `unauthenticated` means the ticket id or secret is wrong (FR-SEC-033): retrying will never help. Anything else is
   * transient (offline, a proxy error) and polling keeps retrying it. */
  errorCode: string | null;
}

export interface TicketStreamDeps {
  createSocket?: (url: string, protocols: string[]) => WebSocket;
  setInterval?: typeof setInterval;
  clearInterval?: typeof clearInterval;
  setTimeout?: typeof setTimeout;
  clearTimeout?: typeof clearTimeout;
  pollIntervalMs?: number;
  openTimeoutMs?: number;
}

function isVisitorTicketView(data: unknown): data is VisitorTicketView {
  return typeof data === "object" && data !== null && typeof (data as { ticket_id?: unknown }).ticket_id === "string";
}

/** A stable default so an omitted `deps` never forces the effect below to reconnect on every render. */
const EMPTY_DEPS: TicketStreamDeps = {};

/**
 * Live position, estimate and now-serving token over the {@code ticket:} realtime topic (§21.2, FR-MOB-013), falling
 * back to HTTP polling every 15 s when WebSocket is unavailable (FR-QUE-084). Never shows a polled or stale figure as
 * current (FR-MOB-040): callers read {@link TicketStreamState.live} and {@link TicketStreamState.lastUpdatedAt} to
 * decide how to label what they show.
 */
export function useTicketStream(
  client: ApiClient | null,
  apiOrigin: string | null,
  ticketId: string,
  credential: string,
  deps: TicketStreamDeps = EMPTY_DEPS,
): TicketStreamState {
  const [state, setState] = useState<TicketStreamState>({ view: null, live: false, lastUpdatedAt: null, errorCode: null });

  useEffect(() => {
    if (!client || apiOrigin === null) return undefined;
    let cancelled = false;
    let socket: WebSocket | null = null;
    let pollTimer: ReturnType<typeof setInterval> | null = null;
    let openTimer: ReturnType<typeof setTimeout> | null = null;

    const createSocket = deps.createSocket ?? ((url, protocols) => new WebSocket(url, protocols));
    const setIntervalImpl = deps.setInterval ?? setInterval;
    const clearIntervalImpl = deps.clearInterval ?? clearInterval;
    const setTimeoutImpl = deps.setTimeout ?? setTimeout;
    const clearTimeoutImpl = deps.clearTimeout ?? clearTimeout;
    const pollIntervalMs = deps.pollIntervalMs ?? POLL_INTERVAL_MS;
    const openTimeoutMs = deps.openTimeoutMs ?? OPEN_TIMEOUT_MS;

    function apply(view: VisitorTicketView, live: boolean) {
      if (cancelled) return;
      setState({ view, live, lastUpdatedAt: new Date(), errorCode: null });
    }

    async function poll() {
      try {
        const view = await client!.tickets.visitorView(ticketId, credential);
        apply(view, false);
      } catch (cause) {
        if (cancelled) return;
        const code = cause instanceof ApiRequestError ? cause.code : "network_error";
        setState((previous) => ({ ...previous, live: false, errorCode: code }));
        // A wrong or unknown ticket credential never becomes right on retry (FR-SEC-033): stop hammering the API.
        if (code === "unauthenticated") stopPolling();
      }
    }

    function startPolling() {
      if (pollTimer) return;
      void poll();
      pollTimer = setIntervalImpl(() => void poll(), pollIntervalMs);
    }

    function stopPolling() {
      if (pollTimer) {
        clearIntervalImpl(pollTimer);
        pollTimer = null;
      }
    }

    function connect() {
      let opened: WebSocket;
      try {
        opened = createSocket(streamUrl(apiOrigin!), [STREAM_PROTOCOL, `ticket.${ticketId}.${credential}`]);
      } catch {
        startPolling();
        return;
      }
      socket = opened;
      openTimer = setTimeoutImpl(() => opened.close(), openTimeoutMs);

      opened.onopen = () => {
        if (openTimer) clearTimeoutImpl(openTimer);
        opened.send(JSON.stringify({ frame: "subscribe", topics: [`ticket:${ticketId}`] }));
        stopPolling();
      };
      opened.onmessage = (event: MessageEvent<string>) => {
        let frame: { frame?: string; data?: unknown } | undefined;
        try {
          frame = JSON.parse(event.data) as { frame?: string; data?: unknown };
        } catch {
          return;
        }
        if (frame.frame === "snapshot" && isVisitorTicketView(frame.data)) {
          apply(frame.data, true);
        } else if (frame.frame === "event") {
          // The frame itself only carries the transition; re-read over REST so derived fields (now serving) stay accurate.
          void poll();
        } else if (frame.frame === "denied" || frame.frame === "error") {
          opened.close();
        }
      };
      opened.onclose = () => {
        if (openTimer) clearTimeoutImpl(openTimer);
        startPolling();
      };
      opened.onerror = () => {
        opened.close();
      };
    }

    connect();
    return () => {
      cancelled = true;
      stopPolling();
      if (openTimer) clearTimeoutImpl(openTimer);
      socket?.close();
    };
  }, [client, apiOrigin, ticketId, credential, deps]);

  return state;
}
