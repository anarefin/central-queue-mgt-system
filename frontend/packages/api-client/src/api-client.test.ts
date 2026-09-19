import { describe, expect, it, vi } from "vitest";
import { ApiClient, ApiRequestError, loadRuntimeConfig, RuntimeConfigError } from "./index";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("loadRuntimeConfig", () => {
  it("reads apiOrigin and strips trailing slashes", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(json(200, { apiOrigin: "https://qms.example.org//" }));
    await expect(loadRuntimeConfig(fetchImpl as unknown as typeof fetch)).resolves.toEqual({
      apiOrigin: "https://qms.example.org",
    });
    expect(fetchImpl).toHaveBeenCalledWith("/config.json", { cache: "no-store" });
  });

  it("accepts an empty apiOrigin meaning same origin", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(json(200, { apiOrigin: "" }));
    await expect(loadRuntimeConfig(fetchImpl as unknown as typeof fetch)).resolves.toEqual({ apiOrigin: "" });
  });

  it.each([
    ["a missing file", () => Promise.resolve(new Response("nope", { status: 404 }))],
    ["a network failure", () => Promise.reject(new TypeError("offline"))],
    ["a non-object body", () => Promise.resolve(json(200, []))],
    ["a non-string apiOrigin", () => Promise.resolve(json(200, { apiOrigin: 3 }))],
  ])("rejects %s with RuntimeConfigError", async (_name, impl) => {
    await expect(loadRuntimeConfig(vi.fn(impl) as unknown as typeof fetch)).rejects.toBeInstanceOf(RuntimeConfigError);
  });
});

describe("ApiClient", () => {
  it("calls the /api/v1 base path on the configured origin", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(json(200, { status: "up" }));
    const client = new ApiClient({ apiOrigin: "https://q.example/", fetch: fetchImpl as unknown as typeof fetch });

    await expect(client.health.live()).resolves.toEqual({ status: "up" });

    expect(fetchImpl.mock.calls[0]?.[0]).toBe("https://q.example/api/v1/health/live");
  });

  it("sends the in-memory bearer token and Accept-Language when available", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(json(200, { status: "up" }));
    const client = new ApiClient({
      apiOrigin: "",
      fetch: fetchImpl as unknown as typeof fetch,
      getAccessToken: () => "tok",
      getLanguage: () => "bn",
    });

    await client.health.ready();

    const headers = (fetchImpl.mock.calls[0]?.[1] as RequestInit).headers as Record<string, string>;
    expect(headers.Authorization).toBe("Bearer tok");
    expect(headers["Accept-Language"]).toBe("bn");
  });

  it("omits Authorization when there is no token", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(json(200, { status: "up" }));
    await new ApiClient({ apiOrigin: "", fetch: fetchImpl as unknown as typeof fetch }).health.live();
    const headers = (fetchImpl.mock.calls[0]?.[1] as RequestInit).headers as Record<string, string>;
    expect(headers.Authorization).toBeUndefined();
  });

  it("turns an error envelope into ApiRequestError with code and trace id", async () => {
    const envelope = { error: { code: "unavailable", message: "down", trace_id: "abc-123" } };
    const client = new ApiClient({
      apiOrigin: "",
      fetch: vi.fn().mockResolvedValue(json(503, envelope)) as unknown as typeof fetch,
    });

    const error = await client.health.ready().catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiRequestError);
    expect(error).toMatchObject({ status: 503, code: "unavailable", traceId: "abc-123" });
  });

  it("does not trust an unknown error code from a non-conforming body", async () => {
    const client = new ApiClient({
      apiOrigin: "",
      fetch: vi.fn().mockResolvedValue(json(500, { error: { code: "made_up", message: "x", trace_id: "t" } })) as unknown as typeof fetch,
    });
    await expect(client.health.live()).rejects.toMatchObject({ code: "unexpected_response" });
  });

  it("reports a proxy HTML error page as unexpected_response", async () => {
    const client = new ApiClient({
      apiOrigin: "",
      fetch: vi.fn().mockResolvedValue(new Response("<html>502</html>", { status: 502 })) as unknown as typeof fetch,
    });
    await expect(client.health.live()).rejects.toMatchObject({ status: 502, code: "unexpected_response" });
  });

  it("reports a network failure as network_error with status 0", async () => {
    const client = new ApiClient({
      apiOrigin: "",
      fetch: vi.fn().mockRejectedValue(new TypeError("Failed to fetch")) as unknown as typeof fetch,
    });
    await expect(client.health.live()).rejects.toMatchObject({ status: 0, code: "network_error" });
  });
});
