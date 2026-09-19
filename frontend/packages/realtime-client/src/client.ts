import type {
  Diagnostics,
  RealtimeClient,
  RealtimeClientOptions,
  RealtimeListener,
  SocketLike,
  TopicSnapshot,
} from "./types";

/** The subprotocol the hub speaks; the token is offered beside it because a browser cannot set `Authorization` (SRS §21.1). */
export const STREAM_PROTOCOL = "qms.v1";
const STREAM_PATH = "/api/v1/stream";
/** The hub closed the socket because its token expired without a `reauth` (4401), or its user was disabled or changed (4403). */
const TOKEN_EXPIRED = 4401;
const PRINCIPAL_CHANGED = 4403;

/** The `exp` of a JWT in milliseconds since the epoch, or null when the token is not one we can read (it is not verified here). */
function expiryMs(token: string): number | null {
  try {
    const payload = token.split(".")[1];
    if (!payload) return null;
    const claims = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/"))) as { exp?: unknown };
    return typeof claims.exp === "number" ? claims.exp * 1000 : null;
  } catch {
    return null;
  }
}

/** `ws(s)://<origin>/api/v1/stream` for the configured API origin; an empty origin means the page's own. */
export function streamUrl(apiOrigin: string, pageOrigin: string = globalThis.location?.origin ?? ""): string {
  const origin = (apiOrigin === "" ? pageOrigin : apiOrigin).replace(/\/+$/, "");
  return `${origin.replace(/^http/, "ws")}${STREAM_PATH}`;
}

interface Topic {
  listeners: Set<RealtimeListener>;
  /** The newest seq applied; null until the first snapshot. Anything at or below it is a duplicate (FR-QUE-082). */
  lastSeq: number | null;
  epoch: string | null;
  /** A listener joined after the snapshot, so the next one must reach everybody even if its seq is not newer. */
  refresh: boolean;
  /** We noticed a gap and asked to be caught up; events are ignored until the hub answers, so we ask only once. */
  catchingUp: boolean;
}

type Frame = Record<string, unknown>;

/**
 * The client of the realtime hub (SRS §21). It keeps one WebSocket for every topic anybody listens to and hides what goes
 * wrong with it: it sends a heartbeat and reconnects after two missed (§21.1), tells the hub the last seq it applied so the
 * hub can replay what was missed or resync it (FR-QUE-081), applies each event once and in order (FR-QUE-082), and where a
 * WebSocket cannot be had it polls the API for snapshots (FR-QUE-084). It sends a `reauth` frame with a fresh access token
 * before the one it holds expires, so the hub does not close the socket (ADR-0009), and after the hub drops it for an
 * expired token or a changed user it comes back with a token from the API. Nothing here is about tickets: what a topic's data
 * means is for its listener.
 */
export function createRealtimeClient(options: RealtimeClientOptions): RealtimeClient {
  const heartbeatMs = options.heartbeatIntervalMs ?? 20_000;
  const missedLimit = options.missedHeartbeats ?? 2;
  const pollMs = options.pollIntervalMs ?? 5_000;
  const fallbackAfter = options.fallbackAfterFailures ?? 2;
  const baseDelay = options.reconnectDelayMs ?? 1_000;
  const maxDelay = options.maxReconnectDelayMs ?? 30_000;
  const reauthLead = options.reauthLeadMs ?? 30_000;
  const reauthRetry = options.reauthRetryMs ?? 2_000;
  const createSocket = options.createSocket ?? ((url: string, protocols: string[]) => new WebSocket(url, protocols) as unknown as SocketLike);

  const topics = new Map<string, Topic>();
  const watchers = new Set<(diagnostics: Diagnostics) => void>();
  let state: Diagnostics = { transport: "idle", degraded: false, reconnects: 0, lastError: null };

  let socket: SocketLike | null = null;
  let opened = false;
  let failures = 0;
  let lastHeard = 0;
  let heartbeat: ReturnType<typeof setInterval> | undefined;
  let watchdog: ReturnType<typeof setInterval> | undefined;
  let redial: ReturnType<typeof setTimeout> | undefined;
  let poll: ReturnType<typeof setInterval> | undefined;
  let pollInFlight = false;
  let wanted = false;
  /** The token the hub currently holds for this socket, and the timer that will send it a fresher one. */
  let heldToken: string | null = null;
  let reauthTimer: ReturnType<typeof setTimeout> | undefined;
  /** The hub dropped us over our token, so the one `getAccessToken` gives is not to be trusted on the next connection. */
  let tokenStale = false;

  function change(patch: Partial<Diagnostics>): void {
    state = { ...state, ...patch };
    watchers.forEach((watcher) => watcher(state));
  }

  // ---- connecting -------------------------------------------------------------------------------------------

  function connect(): void {
    redial = undefined;
    if (!wanted || socket) return;
    if (tokenStale && options.refreshAccessToken) {
      tokenStale = false;
      const again = () => connect();
      options.refreshAccessToken().then(again, again);
      return;
    }
    const token = options.getAccessToken();
    if (token === null) {
      // Signed out, or the first token is not here yet: nothing to authenticate with, so try again shortly. That is not
      // a WebSocket that will not open, so it does not start polling.
      change({ lastError: "no access token" });
      schedule();
      return;
    }
    if (state.transport !== "polling") change({ transport: "connecting" });
    opened = false;
    let next: SocketLike;
    try {
      next = createSocket(options.url, [STREAM_PROTOCOL, `bearer.${token}`]);
    } catch (cause) {
      failed(String(cause));
      return;
    }
    socket = next;
    heldToken = token;
    next.onopen = () => {
      if (socket !== next) return;
      opened = true;
      failures = 0;
      lastHeard = Date.now();
      topics.forEach((topic) => (topic.catchingUp = false)); // whatever we were waiting for died with the last socket
      stopPolling();
      change({ transport: "websocket", degraded: false, lastError: null });
      heartbeat = setInterval(() => send({ frame: "heartbeat" }), heartbeatMs);
      watchdog = setInterval(listen, Math.max(Math.floor(heartbeatMs / 4), 1));
      resubscribe([...topics.keys()]);
      armReauth();
    };
    next.onmessage = (event) => {
      if (socket !== next) return;
      lastHeard = Date.now();
      receive(event.data);
    };
    next.onerror = () => {
      if (socket === next && !opened) change({ lastError: "the WebSocket could not be opened" });
    };
    next.onclose = (event) => {
      if (socket !== next) return;
      if (event?.code === TOKEN_EXPIRED || event?.code === PRINCIPAL_CHANGED) tokenStale = true;
      lost();
    };
  }

  /** The socket is gone, whether it never opened, was dropped or went quiet: dial again, and poll while it will not open. */
  function lost(): void {
    const dead = socket;
    socket = null;
    clearInterval(heartbeat);
    clearInterval(watchdog);
    clearTimeout(reauthTimer);
    heartbeat = watchdog = reauthTimer = undefined;
    if (dead) {
      dead.onopen = dead.onmessage = dead.onclose = dead.onerror = null;
      try {
        dead.close();
      } catch {
        // already closed
      }
    }
    if (!wanted) return;
    change({ reconnects: state.reconnects + 1 });
    if (opened) {
      failures = 0;
      schedule();
    } else {
      failed(state.lastError ?? "the WebSocket could not be opened");
    }
  }

  function failed(reason: string): void {
    failures += 1;
    change({ lastError: reason });
    if (failures >= fallbackAfter) startPolling();
    schedule();
  }

  function schedule(): void {
    if (redial !== undefined || !wanted) return;
    const delay = Math.min(baseDelay * 2 ** Math.max(failures - 1, 0), maxDelay);
    redial = setTimeout(connect, delay);
  }

  /** Gives up on a hub that has said nothing for the missed heartbeats (§21.1); we send ours on a timer of their own. */
  function listen(): void {
    if (!socket || Date.now() - lastHeard <= heartbeatMs * missedLimit) return;
    change({ lastError: "the hub stopped answering" });
    lost();
  }

  // ---- re-authenticating before the token expires (ADR-0009) -------------------------------------------------

  /** Schedules the `reauth` for `reauthLead` before the held token expires; a token we cannot read the expiry of is not scheduled. */
  function armReauth(): void {
    clearTimeout(reauthTimer);
    reauthTimer = undefined;
    const expiry = heldToken === null ? null : expiryMs(heldToken);
    if (expiry === null || !socket) return;
    reauthTimer = setTimeout(() => void reauth(), Math.max(expiry - Date.now() - reauthLead, 0));
  }

  /** Sends the hub a fresher token than the one it holds; until there is one, looks again shortly, until the token is spent. */
  async function reauth(): Promise<void> {
    reauthTimer = undefined;
    const current = socket;
    if (!current || !opened) return;
    let token = options.getAccessToken();
    if ((token === null || token === heldToken) && options.refreshAccessToken) {
      try {
        token = await options.refreshAccessToken();
      } catch {
        token = null;
      }
    }
    if (socket !== current) return; // the socket went away while we waited
    if (token !== null && token !== heldToken) {
      heldToken = token;
      send({ frame: "reauth", token });
      armReauth();
      return;
    }
    const expiry = heldToken === null ? null : expiryMs(heldToken);
    if (expiry !== null && Date.now() < expiry) reauthTimer = setTimeout(() => void reauth(), reauthRetry);
    // Otherwise the hub closes the socket at the expiry and the reconnect brings a new token.
  }

  function send(frame: Frame): void {
    try {
      socket?.send(JSON.stringify(frame));
    } catch {
      // A send on a socket that is closing fails; the close handler does the recovery.
    }
  }

  /** Asks for topics with what we already hold, so the hub replays the gap or resyncs us (FR-QUE-081). */
  function resubscribe(names: string[]): void {
    if (names.length === 0) return;
    send({
      frame: "subscribe",
      topics: names.map((name) => {
        const topic = topics.get(name);
        return topic && topic.lastSeq !== null && topic.epoch !== null && !topic.refresh
          ? { topic: name, last_seq: topic.lastSeq, epoch: topic.epoch }
          : { topic: name };
      }),
    });
  }

  // ---- frames -----------------------------------------------------------------------------------------------

  function receive(raw: unknown): void {
    let frame: Frame;
    try {
      frame = JSON.parse(String(raw)) as Frame;
    } catch {
      return;
    }
    const topic = typeof frame.topic === "string" ? topics.get(frame.topic) : undefined;
    switch (frame.frame) {
      case "snapshot":
        if (topic) topic.catchingUp = false;
        if (topic) applySnapshot(topic, { topic: String(frame.topic), seq: Number(frame.seq), epoch: String(frame.epoch), data: object(frame.data) }, frame.resync === true);
        break;
      case "replay":
        if (topic) topic.catchingUp = false;
        break;
      case "event":
        if (topic) applyEvent(topic, frame);
        break;
      case "denied":
        if (topic) tell(topic, { kind: "denied", topic: String(frame.topic), code: String(frame.code) });
        break;
      case "error":
        if (frame.code === "unauthorized") {
          // The hub did not accept our reauth, so it will close the socket at the old expiry: start over with a fresh token now.
          tokenStale = true;
          change({ lastError: "the hub refused the token" });
          lost();
        }
        break;
      default:
        break; // heartbeat, replay marker, reauth acknowledgement
    }
  }

  function applySnapshot(topic: Topic, snapshot: TopicSnapshot, resync: boolean, polled = false): void {
    // A poll that finds nothing new is not news; a snapshot from the hub, or one after an epoch change, always is.
    if (polled && !topic.refresh && topic.epoch === snapshot.epoch && topic.lastSeq !== null && snapshot.seq <= topic.lastSeq) return;
    topic.lastSeq = snapshot.seq;
    topic.epoch = snapshot.epoch;
    topic.refresh = false;
    tell(topic, { kind: "snapshot", topic: snapshot.topic, seq: snapshot.seq, data: snapshot.data, resync });
  }

  function applyEvent(topic: Topic, frame: Frame): void {
    const seq = Number(frame.seq);
    if (topic.lastSeq === null || seq <= topic.lastSeq) return; // a duplicate, or an event ahead of its snapshot (FR-QUE-082)
    if (topic.catchingUp) return;
    if (seq > topic.lastSeq + 1) {
      // A gap: ask to be caught up rather than apply out of order.
      topic.catchingUp = true;
      resubscribe([String(frame.topic)]);
      return;
    }
    topic.lastSeq = seq;
    tell(topic, {
      kind: "event",
      topic: String(frame.topic),
      seq,
      type: String(frame.type),
      occurredAt: String(frame.occurred_at),
      data: object(frame.data),
    });
  }

  function tell(topic: Topic, update: Parameters<RealtimeListener>[0]): void {
    for (const listener of [...topic.listeners]) {
      try {
        listener(update);
      } catch {
        // One listener's failure must not stop the others, or the next event.
      }
    }
  }

  // ---- polling (FR-QUE-084) ---------------------------------------------------------------------------------

  function startPolling(): void {
    if (poll !== undefined || !options.fetchSnapshot) return;
    change({ transport: "polling", degraded: true });
    poll = setInterval(() => void pollOnce(), pollMs);
    void pollOnce();
  }

  function stopPolling(): void {
    if (poll !== undefined) clearInterval(poll);
    poll = undefined;
  }

  async function pollOnce(): Promise<void> {
    const fetchSnapshot = options.fetchSnapshot;
    if (pollInFlight || !fetchSnapshot) return;
    pollInFlight = true;
    try {
      for (const [name, topic] of [...topics]) {
        try {
          applySnapshot(topic, await fetchSnapshot(name), false, true);
        } catch (cause) {
          change({ lastError: `polling ${name} failed: ${String(cause)}` });
        }
      }
    } finally {
      pollInFlight = false;
    }
  }

  // ---- the public face --------------------------------------------------------------------------------------

  function start(): void {
    if (wanted) return;
    wanted = true;
    failures = 0;
    connect();
  }

  function stop(): void {
    wanted = false;
    clearTimeout(redial);
    redial = undefined;
    stopPolling();
    const dead = socket;
    socket = null;
    clearInterval(heartbeat);
    clearInterval(watchdog);
    clearTimeout(reauthTimer);
    heartbeat = watchdog = reauthTimer = undefined;
    if (dead) {
      dead.onopen = dead.onmessage = dead.onclose = dead.onerror = null;
      try {
        dead.close(1000, "done");
      } catch {
        // already closed
      }
    }
    change({ transport: "idle", degraded: false });
  }

  return {
    subscribe(name, listener) {
      let topic = topics.get(name);
      if (!topic) {
        topic = { listeners: new Set(), lastSeq: null, epoch: null, refresh: false, catchingUp: false };
        topics.set(name, topic);
        topic.listeners.add(listener);
        start();
        if (socket && state.transport === "websocket") resubscribe([name]);
        else if (state.transport === "polling") void pollOnce();
      } else {
        // The snapshot already came for the others; a fresh one brings this listener up to date.
        topic.listeners.add(listener);
        topic.refresh = true;
        if (socket && state.transport === "websocket") resubscribe([name]);
        else if (state.transport === "polling") void pollOnce();
      }
      return () => {
        const current = topics.get(name);
        if (!current) return;
        current.listeners.delete(listener);
        if (current.listeners.size > 0) return;
        topics.delete(name);
        if (socket && state.transport === "websocket") send({ frame: "unsubscribe", topics: [name] });
        if (topics.size === 0) stop();
      };
    },
    diagnostics: () => state,
    onDiagnostics(listener) {
      watchers.add(listener);
      return () => watchers.delete(listener);
    },
    close: stop,
  };
}

function object(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null ? (value as Record<string, unknown>) : {};
}
