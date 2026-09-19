/** How the client is reaching the hub right now. */
export type Transport = "idle" | "connecting" | "websocket" | "polling";

/**
 * What the client knows about its own connection, for diagnostics screens and support. It is never shown to a visitor
 * (FR-QUE-084): a visitor's page looks the same whether it is live or polling.
 */
export interface Diagnostics {
  transport: Transport;
  /** True while the client is polling because a WebSocket could not be had. */
  degraded: boolean;
  /** How many times the socket has been lost and dialled again. */
  reconnects: number;
  lastError: string | null;
}

/** A topic's state as of `seq`: what a subscriber, or a client that polls, applies before any delta (SRS §21.1). */
export interface TopicSnapshot {
  topic: string;
  seq: number;
  epoch: string;
  data: Record<string, unknown>;
}

/** What a listener is told, always in `seq` order and never twice (FR-QUE-082). */
export type RealtimeUpdate =
  | { kind: "snapshot"; topic: string; seq: number; data: Record<string, unknown>; resync: boolean }
  | { kind: "event"; topic: string; seq: number; type: string; occurredAt: string; data: Record<string, unknown> }
  /** The hub refused the subscription; `code` is `forbidden`, `not_found`, `invalid_topic` or `unknown_topic`. */
  | { kind: "denied"; topic: string; code: string };

export type RealtimeListener = (update: RealtimeUpdate) => void;

/** The parts of a browser WebSocket the client uses, so tests can stand in for one. */
export interface SocketLike {
  send(data: string): void;
  close(code?: number, reason?: string): void;
  onopen: ((event: unknown) => void) | null;
  onmessage: ((event: { data: unknown }) => void) | null;
  onclose: ((event: { code?: number }) => void) | null;
  onerror: ((event: unknown) => void) | null;
}

export interface RealtimeClientOptions {
  /** `ws(s)://…/api/v1/stream`; see {@link streamUrl}. */
  url: string;
  /** The current access token, or null when signed out. It is read at every connection, so a refreshed token is used. */
  getAccessToken: () => string | null;
  /** How to ask the API for a topic's snapshot; without it the client cannot fall back to polling. */
  fetchSnapshot?: (topic: string) => Promise<TopicSnapshot>;
  /**
   * A fresh access token, refreshed from the API if need be, or null when there is none. It is used to re-authenticate the
   * socket when the token it holds is about to expire and `getAccessToken` has not yet been given a new one, and to
   * reconnect after the hub dropped the socket because the token expired or the user's roles changed (ADR-0009): a token
   * from before a change is no longer good for a socket.
   */
  refreshAccessToken?: () => Promise<string | null>;
  /** How long before its token expires the socket sends `reauth` with a fresh one (ADR-0009, SRS §21.1). Default 30 000 ms. */
  reauthLeadMs?: number;
  /** How long to wait before looking again for a fresher token when there was none to send. Default 2 000 ms. */
  reauthRetryMs?: number;
  createSocket?: (url: string, protocols: string[]) => SocketLike;
  /** Heartbeat interval each way (SRS §21.1). Default 20 000 ms. */
  heartbeatIntervalMs?: number;
  /** Heartbeat intervals of silence after which the client reconnects (SRS §21.1). Default 2. */
  missedHeartbeats?: number;
  /** How often to poll while WebSocket is unavailable (FR-QUE-084): 5 000 ms for displays, 15 000 ms for mobile. Default 5 000. */
  pollIntervalMs?: number;
  /** Failed connection attempts, in a row, before the client starts polling. Default 2. */
  fallbackAfterFailures?: number;
  /** First wait before dialling again; it doubles up to {@link maxReconnectDelayMs}. Default 1 000 ms. */
  reconnectDelayMs?: number;
  maxReconnectDelayMs?: number;
}

export interface RealtimeClient {
  /**
   * Listens to a topic. The listener gets the snapshot first and then each delta once, in order, across reconnects.
   * Returns the function that stops listening.
   */
  subscribe(topic: string, listener: RealtimeListener): () => void;
  diagnostics(): Diagnostics;
  onDiagnostics(listener: (diagnostics: Diagnostics) => void): () => void;
  /** Closes the connection and stops polling; subscribing again starts them again. */
  close(): void;
}
