import { describe, expect, it, vi } from "vitest";
import { ApiClient, ApiRequestError, loadRuntimeConfig, newIdempotencyKey, RuntimeConfigError } from "./index";

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

describe("ApiClient site hierarchy", () => {
  it("maps sites, zones and counters onto the REST paths, deactivating softly and never deleting (FR-CFG-001)", async () => {
    const fetchImpl = vi.fn().mockImplementation(async () => json(200, { items: [] }));
    const client = new ApiClient({ apiOrigin: "", fetch: fetchImpl as unknown as typeof fetch });

    await client.sites.list();
    await client.sites.create({ name: "N", code: "C", timezone: "Asia/Dhaka", address: "A", default_language: "bn", enabled_languages: ["bn", "en"] });
    await client.sites.update("s1", { name: "Renamed" });
    await client.sites.deactivate("s1", "closed");
    await client.sites.activate("s1");
    await client.sites.zones("s1");
    await client.sites.createZone("s1", { name: "Z", floor_label: "Ground", building_label: "Block B" });
    await client.zones.update("z1", { building_label: "" });
    await client.zones.deactivate("z1");
    await client.zones.activate("z1");
    await client.zones.counters("z1");
    await client.zones.createCounter("z1", { label: "3", location_note: "Behind the pillar" });
    await client.counters.update("c1", { label: "4" });
    await client.counters.deactivate("c1");
    await client.counters.activate("c1");

    const calls = fetchImpl.mock.calls.map(([url, init]) => `${(init as RequestInit).method} ${String(url).replace("/api/v1", "")}`);
    expect(calls).toEqual([
      "GET /sites",
      "POST /sites",
      "PATCH /sites/s1",
      "POST /sites/s1/deactivate",
      "POST /sites/s1/activate",
      "GET /sites/s1/zones",
      "POST /sites/s1/zones",
      "PATCH /zones/z1",
      "POST /zones/z1/deactivate",
      "POST /zones/z1/activate",
      "GET /zones/z1/counters",
      "POST /zones/z1/counters",
      "PATCH /counters/c1",
      "POST /counters/c1/deactivate",
      "POST /counters/c1/activate",
    ]);
    expect(JSON.parse(String((fetchImpl.mock.calls[3]?.[1] as RequestInit).body))).toEqual({ reason: "closed" });
    expect(JSON.parse(String((fetchImpl.mock.calls[7]?.[1] as RequestInit).body))).toEqual({ building_label: "" });
  });
});

describe("ApiClient service catalogue", () => {
  it("maps groups, services, counter links, teams and outcome codes onto the REST paths (FR-CFG-010..015, FR-AGT-032)", async () => {
    const fetchImpl = vi.fn().mockImplementation(async () => json(200, { items: [] }));
    const client = new ApiClient({ apiOrigin: "", fetch: fetchImpl as unknown as typeof fetch });

    await client.catalogue.groups("s1");
    await client.catalogue.createGroup("s1", { name_i18n: { bn: "বহির্বিভাগ" }, token_prefix: "OPD" });
    await client.catalogue.updateGroup("g1", { display_order: 2 });
    await client.catalogue.deactivateGroup("g1", "closed");
    await client.catalogue.activateGroup("g1");
    await client.catalogue.services("g1");
    await client.catalogue.createService("g1", {
      name_i18n: { bn: "পরামর্শ" },
      token_prefix: "CON",
      expected_minutes: 12,
      sla_wait_minutes: 30,
      channels: ["kiosk"],
      visitor_identifier: "mandatory",
      booking_mode: "walk_in_only",
    });
    await client.catalogue.updateService("v1", { icon: "" });
    await client.catalogue.deactivateService("v1");
    await client.catalogue.activateService("v1");
    await client.catalogue.deleteService("v1");
    await client.catalogue.counterOptions("g1");
    await client.catalogue.links("v1");
    await client.catalogue.link("v1", "c1", 2);
    await client.catalogue.link("v1", "c2");
    await client.catalogue.unlink("v1", "c1");
    await client.catalogue.outcomes("v1");
    await client.catalogue.createOutcome("v1", { code: "resolved", label_i18n: { bn: "সমাধান" } });
    await client.catalogue.updateOutcome("o1", { display_order: 1 });
    await client.catalogue.deactivateOutcome("o1");
    await client.catalogue.activateOutcome("o1");
    await client.catalogue.team("g1");
    await client.catalogue.addMember("g1", "u1");
    await client.catalogue.removeMember("g1", "u1");
    await client.users.list();

    const calls = fetchImpl.mock.calls.map(([url, init]) => `${(init as RequestInit).method} ${String(url).replace("/api/v1", "")}`);
    expect(calls).toEqual([
      "GET /sites/s1/service-groups",
      "POST /sites/s1/service-groups",
      "PATCH /service-groups/g1",
      "POST /service-groups/g1/deactivate",
      "POST /service-groups/g1/activate",
      "GET /service-groups/g1/services",
      "POST /service-groups/g1/services",
      "PATCH /services/v1",
      "POST /services/v1/deactivate",
      "POST /services/v1/activate",
      "DELETE /services/v1",
      "GET /service-groups/g1/counters",
      "GET /services/v1/counters",
      "PUT /services/v1/counters/c1",
      "PUT /services/v1/counters/c2",
      "DELETE /services/v1/counters/c1",
      "GET /services/v1/outcome-codes",
      "POST /services/v1/outcome-codes",
      "PATCH /outcome-codes/o1",
      "POST /outcome-codes/o1/deactivate",
      "POST /outcome-codes/o1/activate",
      "GET /service-groups/g1/team",
      "POST /service-groups/g1/team/members",
      "DELETE /service-groups/g1/team/members/u1",
      "GET /users?limit=200",
    ]);
    const body = (index: number) => JSON.parse(String((fetchImpl.mock.calls[index]?.[1] as RequestInit).body));
    expect(body(3)).toEqual({ reason: "closed" });
    expect(body(13)).toEqual({ preference_weight: 2 });
    expect((fetchImpl.mock.calls[14]?.[1] as RequestInit).body).toBeUndefined();
    expect(body(22)).toEqual({ user_id: "u1" });
  });
  it("issues a ticket with an Idempotency-Key that survives a token refresh retry, and reads tickets, queues and site services", async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }))
      .mockImplementation(async () => json(201, { id: "t1" }));
    let token = "old";
    const client = new ApiClient({
      apiOrigin: "",
      fetch: fetchImpl as unknown as typeof fetch,
      getAccessToken: () => token,
      onTokenInvalid: async () => {
        token = "new";
        return true;
      },
    });

    await client.tickets.issue({ service_id: "v1", origin_channel: "reception" }, "key-1");
    await client.tickets.get("t1");
    await client.queues.snapshot("v1");
    await client.queues.snapshot("v1", 5);
    await client.sites.services("s1");
    await client.sites.services("s1", "reception");

    const calls = fetchImpl.mock.calls.map(([url, init]) => `${(init as RequestInit).method} ${String(url).replace("/api/v1", "")}`);
    expect(calls).toEqual([
      "POST /tickets",
      "POST /tickets",
      "GET /tickets/t1",
      "GET /queues/v1",
      "GET /queues/v1?limit=5",
      "GET /sites/s1/services",
      "GET /sites/s1/services?channel=reception",
    ]);
    const headers = (index: number) => (fetchImpl.mock.calls[index]?.[1] as RequestInit).headers as Record<string, string>;
    expect(headers(0)["Idempotency-Key"]).toBe("key-1");
    expect(headers(1)["Idempotency-Key"]).toBe("key-1");
    expect(headers(1).Authorization).toBe("Bearer new");
    expect(headers(2)["Idempotency-Key"]).toBeUndefined();
    expect(JSON.parse(String((fetchImpl.mock.calls[0]?.[1] as RequestInit).body))).toEqual({ service_id: "v1", origin_channel: "reception" });
  });

  it("sets, removes, lists and previews numbering rules per service or service group", async () => {
    const fetchImpl = vi.fn().mockImplementation(async () => json(200, { items: [], rule: null, affected_waiting_tickets: 0 }));
    const client = new ApiClient({ apiOrigin: "", fetch: fetchImpl as unknown as typeof fetch });

    await client.numbering.rules("s1");
    await client.numbering.setRule("service_group", "g1", { prefix_source: "fixed", fixed_prefix: "VIP", padding: 0, separator: "" });
    await client.numbering.setRule("service", "v1", { reset_boundary: "weekly", reset_time: "04:30" });
    await client.numbering.removeRule("service", "v1");
    await client.numbering.preview("service_group", "g1");
    await client.numbering.preview("service", "v1");

    const calls = fetchImpl.mock.calls.map(([url, init]) => `${(init as RequestInit).method} ${String(url).replace("/api/v1", "")}`);
    expect(calls).toEqual([
      "GET /sites/s1/numbering-rules",
      "PUT /service-groups/g1/numbering-rule",
      "PUT /services/v1/numbering-rule",
      "DELETE /services/v1/numbering-rule",
      "GET /service-groups/g1/numbering-preview",
      "GET /services/v1/numbering-preview",
    ]);
    expect(JSON.parse(String((fetchImpl.mock.calls[1]?.[1] as RequestInit).body))).toEqual({ prefix_source: "fixed", fixed_prefix: "VIP", padding: 0, separator: "" });
    expect(JSON.parse(String((fetchImpl.mock.calls[2]?.[1] as RequestInit).body))).toEqual({ reset_boundary: "weekly", reset_time: "04:30" });
  });

  it("makes a new idempotency key each time", () => {
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
  });
});
