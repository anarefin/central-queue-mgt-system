import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createDeviceAuth, type DeviceCredentialStore } from "./device-session";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function tokens(deviceId: string, access: string, refresh: string, expiresIn = 900) {
  return {
    device_id: deviceId,
    kind: "kiosk",
    site_id: "site-1",
    zone_id: null,
    access_token: access,
    token_type: "Bearer",
    expires_in: expiresIn,
    refresh_token: refresh,
  };
}

const TOKEN_INVALID = { error: { code: "token_invalid", message: "expired", trace_id: "t" } };

type Route = (init: RequestInit) => Response | Promise<Response>;

/** An in-memory stand-in for a kiosk/display shell's own storage; the real default is IndexedDB-backed (API-017). */
function memoryStore(initial: string | null = null): DeviceCredentialStore & { value: string | null } {
  const store = {
    value: initial,
    async load() {
      return store.value;
    },
    async save(refreshToken: string) {
      store.value = refreshToken;
    },
    async clear() {
      store.value = null;
    },
  };
  return store;
}

function makeDeviceAuth(routes: Record<string, Route>): ReturnType<typeof buildDeviceAuth<ReturnType<typeof memoryStore>>>;
function makeDeviceAuth<S extends DeviceCredentialStore>(routes: Record<string, Route>, store: S): ReturnType<typeof buildDeviceAuth<S>>;
function makeDeviceAuth(routes: Record<string, Route>, store: DeviceCredentialStore = memoryStore()) {
  return buildDeviceAuth(routes, store);
}

function buildDeviceAuth<S extends DeviceCredentialStore>(routes: Record<string, Route>, store: S) {
  const calls: { path: string; init: RequestInit }[] = [];
  const fetchImpl = vi.fn(async (url: string, init: RequestInit) => {
    const path = url.replace(/^.*\/api\/v1/, "");
    calls.push({ path, init });
    const route = routes[`${init.method} ${path}`];
    if (!route) throw new Error(`unrouted ${init.method} ${path}`);
    return route(init);
  });
  const auth = createDeviceAuth({ apiOrigin: "", fetch: fetchImpl as unknown as typeof fetch }, store);
  return { ...auth, calls, store };
}

const bodyOf = (init: RequestInit) => JSON.parse(String(init.body)) as Record<string, unknown>;

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("DeviceSession", () => {
  it("pairs, holds the access token in memory and persists the refresh token through the store", async () => {
    const { session, store, calls } = makeDeviceAuth({
      "POST /devices/pair": () => json(201, tokens("device-1", "access-1", "refresh-1")),
    });

    const response = await session.pair("ABCD1234");

    expect(response.device_id).toBe("device-1");
    expect(session.status).toBe("paired");
    expect(session.accessToken).toBe("access-1");
    expect(session.device).toEqual({ id: "device-1", kind: "kiosk", siteId: "site-1", zoneId: null });
    expect(store.value).toBe("refresh-1");
    expect(bodyOf(calls[0]!.init)).toEqual({ code: "ABCD1234" });
  });

  it("restore resumes a paired device from its stored refresh credential", async () => {
    const { session, calls } = makeDeviceAuth(
      { "POST /devices/refresh": () => json(200, tokens("device-1", "access-2", "refresh-2")) },
      memoryStore("refresh-1"),
    );

    await session.restore();

    expect(session.status).toBe("paired");
    expect(session.accessToken).toBe("access-2");
    expect(bodyOf(calls[0]!.init)).toEqual({ refresh_token: "refresh-1" });
  });

  it("restore with nothing stored lands on unpaired without calling the API", async () => {
    const { session, calls } = makeDeviceAuth({}, memoryStore(null));

    await session.restore();

    expect(session.status).toBe("unpaired");
    expect(session.accessToken).toBeNull();
    expect(calls).toHaveLength(0);
  });

  it("pairing still succeeds in memory even when the credential store cannot be written to", async () => {
    const unwritableStore: DeviceCredentialStore = {
      load: () => Promise.resolve(null),
      save: () => Promise.reject(new Error("IndexedDB is not available")),
      clear: () => Promise.resolve(),
    };
    const { session } = makeDeviceAuth(
      { "POST /devices/pair": () => json(201, tokens("device-1", "access-1", "refresh-1")) },
      unwritableStore,
    );

    await expect(session.pair("CODE0001")).resolves.toMatchObject({ device_id: "device-1" });

    expect(session.status).toBe("paired");
    expect(session.accessToken).toBe("access-1");
  });

  it("restore never throws even when the credential store itself cannot be read", async () => {
    const brokenStore: DeviceCredentialStore = {
      load: () => Promise.reject(new Error("IndexedDB is not available")),
      save: () => Promise.resolve(),
      clear: () => Promise.resolve(),
    };
    const { session, calls } = makeDeviceAuth({}, brokenStore);

    await expect(session.restore()).resolves.toBeUndefined();

    expect(session.status).toBe("unpaired");
    expect(calls).toHaveLength(0);
  });

  it("a revoked or expired refresh credential forgets the device and clears the store", async () => {
    const { session, store } = makeDeviceAuth(
      { "POST /devices/refresh": () => json(401, TOKEN_INVALID) },
      memoryStore("refresh-1"),
    );

    await session.restore();

    expect(session.status).toBe("unpaired");
    expect(session.accessToken).toBeNull();
    expect(session.device).toBeNull();
    expect(store.value).toBeNull();
  });

  it("refreshes silently before the access token expires, rotating the stored credential", async () => {
    let n = 1;
    const { session, store, calls } = makeDeviceAuth({
      "POST /devices/pair": () => json(201, tokens("device-1", "access-1", "refresh-1", 900)),
      "POST /devices/refresh": () => json(200, tokens("device-1", `access-${++n}`, `refresh-${n}`, 900)),
    });
    await session.pair("CODE0001");

    await vi.advanceTimersByTimeAsync(841_000);

    expect(calls.filter((c) => c.path === "/devices/refresh")).toHaveLength(1);
    expect(session.accessToken).toBe("access-2");
    expect(store.value).toBe("refresh-2");
  });

  it("shares one request between concurrent refreshes", async () => {
    let resolve!: (response: Response) => void;
    const routes: Record<string, Route> = {
      "POST /devices/pair": () => json(201, tokens("device-1", "access-1", "refresh-1")),
      "POST /devices/refresh": () => new Promise<Response>((r) => (resolve = r)),
    };
    const { session, calls } = makeDeviceAuth(routes);
    await session.pair("CODE0001");

    const a = session.refresh();
    const b = session.refresh();
    resolve(json(200, tokens("device-1", "access-2", "refresh-2")));

    expect(await Promise.all([a, b])).toEqual([true, true]);
    expect(calls.filter((c) => c.path === "/devices/refresh")).toHaveLength(1);
  });

  it("retries the refresh-and-retry-once path used by other client calls, e.g. heartbeat", async () => {
    let heartbeats = 0;
    const { client, session, calls } = makeDeviceAuth({
      "POST /devices/pair": () => json(201, tokens("device-1", "stale", "refresh-1")),
      "POST /devices/refresh": () => json(200, tokens("device-1", "fresh", "refresh-2")),
      "POST /devices/device-1/heartbeat": () => (++heartbeats === 1 ? json(401, TOKEN_INVALID) : json(204, undefined)),
    });
    await session.pair("CODE0001");

    await client.devices.heartbeat("device-1", "1.0.0");

    expect(calls.map((c) => c.path)).toEqual([
      "/devices/pair",
      "/devices/device-1/heartbeat",
      "/devices/refresh",
      "/devices/device-1/heartbeat",
    ]);
    expect(session.accessToken).toBe("fresh");
  });

  it("forget clears the in-memory session and the stored credential", async () => {
    const { session, store } = makeDeviceAuth({
      "POST /devices/pair": () => json(201, tokens("device-1", "access-1", "refresh-1")),
    });
    await session.pair("CODE0001");

    await session.forget();

    expect(session.status).toBe("unpaired");
    expect(session.accessToken).toBeNull();
    expect(session.device).toBeNull();
    expect(store.value).toBeNull();
  });
});
