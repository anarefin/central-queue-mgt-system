// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createVisitorAuth, type VisitorAuthSession } from "./index";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function tokens(access: string, expiresIn = 900) {
  return { access_token: access, token_type: "Bearer", expires_in: expiresIn };
}

const INVALID = { error: { code: "token_invalid", message: "expired", trace_id: "t" } };
const RATE_LIMITED = { error: { code: "rate_limited", message: "too many", trace_id: "t", details: { retry_after_seconds: 60 } } };

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
  const auth = createVisitorAuth({ apiOrigin: "", fetch: fetchImpl as unknown as typeof fetch });
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

describe("VisitorAuthSession", () => {
  it("requests then verifies an OTP, keeps the token in memory only and sends it on later calls", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    const { client, session, calls } = makeAuth({
      "POST /auth/visitor/otp/request": () => new Response(null, { status: 204 }),
      "POST /auth/visitor/otp/verify": () => json(200, tokens("access-1")),
      "GET /auth/visitor/me": () => json(200, { id: "1", email: "a@example.com" }),
    });

    await session.requestOtp("a@example.com");
    await session.verifyOtp("a@example.com", "123456");
    await client.visitorAuth.me();

    expect(session.status).toBe("authenticated");
    expect(session.accessToken).toBe("access-1");
    expect(authHeader(calls[2]!.init)).toBe("Bearer access-1");
    expect(authHeader(calls[0]!.init)).toBeUndefined();
    expect(authHeader(calls[1]!.init)).toBeUndefined();
    expect(setItem).not.toHaveBeenCalled();
    expect(localStorage.length + sessionStorage.length).toBe(0);
    expect(document.cookie).not.toContain("access-1");
  });

  it("a rate-limited OTP request leaves the session anonymous and rethrows the API error", async () => {
    const { session } = makeAuth({ "POST /auth/visitor/otp/request": () => json(429, RATE_LIMITED) });

    await expect(session.requestOtp("a@example.com")).rejects.toMatchObject({ code: "rate_limited" });

    expect(session.accessToken).toBeNull();
    expect(session.status).not.toBe("authenticated");
  });

  it("a wrong code leaves the session anonymous and rethrows the API error", async () => {
    const { session } = makeAuth({
      "POST /auth/visitor/otp/verify": () => json(401, { error: { code: "invalid_credentials", message: "no", trace_id: "t" } }),
    });

    await expect(session.verifyOtp("a@example.com", "000000")).rejects.toMatchObject({ code: "invalid_credentials" });

    expect(session.accessToken).toBeNull();
    expect(session.status).not.toBe("authenticated");
  });

  it("restore resumes a session from the refresh cookie", async () => {
    const { session } = makeAuth({ "POST /auth/visitor/refresh": () => json(200, tokens("resumed")) });
    await session.restore();
    expect(session.status).toBe("authenticated");
    expect(session.accessToken).toBe("resumed");
  });

  it("restore with no valid cookie ends anonymous without throwing", async () => {
    const { session } = makeAuth({ "POST /auth/visitor/refresh": () => json(401, INVALID) });
    await expect(session.restore()).resolves.toBeUndefined();
    expect(session.status).toBe("anonymous");
    expect(session.accessToken).toBeNull();
  });

  it("refreshes silently one minute before the access token expires", async () => {
    let n = 1;
    const { session, calls } = makeAuth({
      "POST /auth/visitor/otp/verify": () => json(200, tokens("access-1", 900)),
      "POST /auth/visitor/refresh": () => json(200, tokens(`access-${++n}`, 900)),
    });
    await session.verifyOtp("a@example.com", "123456");

    await vi.advanceTimersByTimeAsync(839_000);
    expect(calls.filter((c) => c.path === "/auth/visitor/refresh")).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(2_000);

    expect(calls.filter((c) => c.path === "/auth/visitor/refresh")).toHaveLength(1);
    expect(session.accessToken).toBe("access-2");
  });

  it("logout tells the server, clears the token and stops refreshing", async () => {
    const { session, calls } = makeAuth({
      "POST /auth/visitor/otp/verify": () => json(200, tokens("access-1")),
      "POST /auth/visitor/logout": () => new Response(null, { status: 204 }),
    });
    await session.verifyOtp("a@example.com", "123456");

    await session.logout();

    expect(session.status).toBe("anonymous");
    expect(session.accessToken).toBeNull();
    expect(calls.map((c) => c.path)).toEqual(["/auth/visitor/otp/verify", "/auth/visitor/logout"]);
    await vi.advanceTimersByTimeAsync(3_600_000);
    expect(calls).toHaveLength(2);
  });

  it("logout still signs out locally when the server cannot be reached", async () => {
    const { session } = makeAuth({
      "POST /auth/visitor/otp/verify": () => json(200, tokens("access-1")),
      "POST /auth/visitor/logout": () => {
        throw new TypeError("offline");
      },
    });
    await session.verifyOtp("a@example.com", "123456");

    await session.logout();

    expect(session.accessToken).toBeNull();
    expect(session.status).toBe("anonymous");
  });
});

// keep the type import used
export type _Session = VisitorAuthSession;
