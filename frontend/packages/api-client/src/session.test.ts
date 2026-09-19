// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createAuth, type AuthSession } from "./index";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function tokens(access: string, expiresIn = 900) {
  return { access_token: access, token_type: "Bearer", expires_in: expiresIn };
}

const INVALID = { error: { code: "token_invalid", message: "expired", trace_id: "t" } };
const BAD_LOGIN = { error: { code: "invalid_credentials", message: "no", trace_id: "t" } };

type Route = (init: RequestInit) => Response | Promise<Response>;

function makeAuth(routes: Record<string, Route>) {
  const calls: { path: string; init: RequestInit }[] = [];
  const fetchImpl = vi.fn(async (url: string, init: RequestInit) => {
    const path = url.replace(/^.*\/api\/v1/, "");
    calls.push({ path, init });
    const route = routes[`${init.method} ${path}`];
    if (!route) throw new Error(`unrouted ${init.method} ${path}`);
    return route(init);
  });
  const auth = createAuth({ apiOrigin: "", fetch: fetchImpl as unknown as typeof fetch });
  return { ...auth, calls };
}

const authHeader = (init: RequestInit) => (init.headers as Record<string, string>).Authorization;

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("AuthSession", () => {
  it("logs in, keeps the token in memory only and sends it on later calls", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    const { client, session, calls } = makeAuth({
      "POST /auth/login": () => json(200, tokens("access-1")),
      "GET /auth/me": () => json(200, { id: "1", username: "u" }),
    });

    await session.login("u", "p");
    await client.auth.me();

    expect(session.status).toBe("authenticated");
    expect(session.accessToken).toBe("access-1");
    expect(authHeader(calls[1]!.init)).toBe("Bearer access-1");
    expect(authHeader(calls[0]!.init)).toBeUndefined();
    expect(setItem).not.toHaveBeenCalled();
    expect(localStorage.length + sessionStorage.length).toBe(0);
    expect(document.cookie).not.toContain("access-1");
  });

  it("a failed login leaves the session anonymous and rethrows the API error", async () => {
    const { session } = makeAuth({ "POST /auth/login": () => json(401, BAD_LOGIN) });

    await expect(session.login("u", "wrong")).rejects.toMatchObject({ code: "invalid_credentials" });

    expect(session.accessToken).toBeNull();
    expect(session.status).not.toBe("authenticated");
  });

  it("restore resumes a session from the refresh cookie", async () => {
    const { session } = makeAuth({ "POST /auth/refresh": () => json(200, tokens("resumed")) });
    await session.restore();
    expect(session.status).toBe("authenticated");
    expect(session.accessToken).toBe("resumed");
  });

  it("restore with no valid cookie ends anonymous without throwing", async () => {
    const { session } = makeAuth({ "POST /auth/refresh": () => json(401, INVALID) });
    await expect(session.restore()).resolves.toBeUndefined();
    expect(session.status).toBe("anonymous");
    expect(session.accessToken).toBeNull();
  });

  it("refreshes silently one minute before the access token expires", async () => {
    let n = 1;
    const { session, calls } = makeAuth({
      "POST /auth/login": () => json(200, tokens("access-1", 900)),
      "POST /auth/refresh": () => json(200, tokens(`access-${++n}`, 900)),
    });
    await session.login("u", "p");

    await vi.advanceTimersByTimeAsync(839_000);
    expect(calls.filter((c) => c.path === "/auth/refresh")).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(2_000);

    expect(calls.filter((c) => c.path === "/auth/refresh")).toHaveLength(1);
    expect(session.accessToken).toBe("access-2");
    await vi.advanceTimersByTimeAsync(840_000);
    expect(session.accessToken).toBe("access-3");
  });

  it("shares one request between concurrent refreshes, because a duplicate would look like token reuse", async () => {
    let resolve!: (response: Response) => void;
    const { session, calls } = makeAuth({
      "POST /auth/refresh": () => new Promise<Response>((r) => (resolve = r)),
    });

    const a = session.refresh();
    const b = session.refresh();
    const c = session.refresh();
    resolve(json(200, tokens("once")));

    expect(await Promise.all([a, b, c])).toEqual([true, true, true]);
    expect(calls.filter((x) => x.path === "/auth/refresh")).toHaveLength(1);
  });

  it("refreshes and retries once when the API says the access token is invalid", async () => {
    let meCalls = 0;
    const { client, session, calls } = makeAuth({
      "POST /auth/login": () => json(200, tokens("stale")),
      "POST /auth/refresh": () => json(200, tokens("fresh")),
      "GET /auth/me": (init) => (++meCalls === 1 ? json(401, INVALID) : json(200, { id: authHeader(init) })),
    });
    await session.login("u", "p");

    const me = await client.auth.me();

    expect(me.id).toBe("Bearer fresh");
    expect(calls.map((c) => c.path)).toEqual(["/auth/login", "/auth/me", "/auth/refresh", "/auth/me"]);
  });

  it("does not loop: a second token_invalid after refreshing is surfaced", async () => {
    const { client, session, calls } = makeAuth({
      "POST /auth/login": () => json(200, tokens("stale")),
      "POST /auth/refresh": () => json(200, tokens("also-rejected")),
      "GET /auth/me": () => json(401, INVALID),
    });
    await session.login("u", "p");

    await expect(client.auth.me()).rejects.toMatchObject({ code: "token_invalid" });

    expect(calls.filter((c) => c.path === "/auth/me")).toHaveLength(2);
    expect(calls.filter((c) => c.path === "/auth/refresh")).toHaveLength(1);
  });

  it("does not try to refresh for a request that sent no token", async () => {
    const { client, calls } = makeAuth({ "GET /auth/me": () => json(401, INVALID) });
    await expect(client.auth.me()).rejects.toMatchObject({ code: "token_invalid" });
    expect(calls.map((c) => c.path)).toEqual(["/auth/me"]);
  });

  it("a refresh the server rejects ends the session, cancels the timer and tells subscribers", async () => {
    const { session, calls } = makeAuth({
      "POST /auth/login": () => json(200, tokens("access-1")),
      "POST /auth/refresh": () => json(401, INVALID),
    });
    const listener = vi.fn();
    await session.login("u", "p");
    session.subscribe(listener);

    await vi.advanceTimersByTimeAsync(841_000);

    expect(session.status).toBe("anonymous");
    expect(session.accessToken).toBeNull();
    expect(listener).toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(3_600_000);
    expect(calls.filter((c) => c.path === "/auth/refresh")).toHaveLength(1);
  });

  it("keeps the session through a network blip and retries shortly", async () => {
    let refreshes = 0;
    const { session } = makeAuth({
      "POST /auth/login": () => json(200, tokens("access-1")),
      "POST /auth/refresh": () => {
        if (++refreshes === 1) throw new TypeError("Failed to fetch");
        return json(200, tokens("access-2"));
      },
    });
    await session.login("u", "p");

    await vi.advanceTimersByTimeAsync(841_000);
    expect(session.status).toBe("authenticated");
    expect(session.accessToken).toBe("access-1");

    await vi.advanceTimersByTimeAsync(11_000);
    expect(session.accessToken).toBe("access-2");
  });

  it("logout tells the server, clears the token and stops refreshing", async () => {
    const { session, calls } = makeAuth({
      "POST /auth/login": () => json(200, tokens("access-1")),
      "POST /auth/logout": () => new Response(null, { status: 204 }),
    });
    await session.login("u", "p");

    await session.logout();

    expect(session.status).toBe("anonymous");
    expect(session.accessToken).toBeNull();
    expect(calls.map((c) => c.path)).toEqual(["/auth/login", "/auth/logout"]);
    await vi.advanceTimersByTimeAsync(3_600_000);
    expect(calls).toHaveLength(2);
  });

  it("logout still signs out locally when the server cannot be reached", async () => {
    const { session } = makeAuth({
      "POST /auth/login": () => json(200, tokens("access-1")),
      "POST /auth/logout": () => {
        throw new TypeError("offline");
      },
    });
    await session.login("u", "p");

    await session.logout();

    expect(session.accessToken).toBeNull();
    expect(session.status).toBe("anonymous");
  });
});

// keep the type import used
export type _Session = AuthSession;
