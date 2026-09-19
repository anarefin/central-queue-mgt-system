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

  it("maps priority classes, the ordering strategy of a group and the queue dry-run onto their paths (FR-QUE-010, FR-QUE-021, FR-QUE-023)", async () => {
    const fetchImpl = vi.fn().mockImplementation(async () => json(200, { items: [] }));
    const client = new ApiClient({ apiOrigin: "", fetch: fetchImpl as unknown as typeof fetch });

    await client.priority.classes();
    await client.priority.createClass({ name_i18n: { en: "Senior" }, headstart_minutes: 20, max_wait_minutes: 45, token_prefix_override: "SC" });
    await client.priority.updateClass("c1", { name_i18n: { en: "Senior" }, headstart_minutes: 30 });
    await client.priority.deactivateClass("c1", "retired");
    await client.priority.deactivateClass("c1");
    await client.priority.activateClass("c1");
    await client.priority.strategy("g1");
    await client.priority.setStrategy("g1", "strict_priority");
    await client.queues.dryRun("v1");
    await client.queues.dryRun("v1", "fifo");

    const calls = fetchImpl.mock.calls.map(([url, init]) => `${(init as RequestInit).method} ${String(url).replace("/api/v1", "")}`);
    expect(calls).toEqual([
      "GET /priority-classes",
      "POST /priority-classes",
      "PUT /priority-classes/c1",
      "POST /priority-classes/c1/deactivate",
      "POST /priority-classes/c1/deactivate",
      "POST /priority-classes/c1/activate",
      "GET /service-groups/g1/routing-strategy",
      "PUT /service-groups/g1/routing-strategy",
      "GET /queues/v1/dry-run",
      "GET /queues/v1/dry-run?strategy=fifo",
    ]);
    const body = (index: number) => JSON.parse(String((fetchImpl.mock.calls[index]?.[1] as RequestInit).body));
    expect(body(1)).toEqual({ name_i18n: { en: "Senior" }, headstart_minutes: 20, max_wait_minutes: 45, token_prefix_override: "SC" });
    expect(body(3)).toEqual({ reason: "retired" });
    expect((fetchImpl.mock.calls[4]?.[1] as RequestInit).body).toBeUndefined();
    expect(body(7)).toEqual({ strategy: "strict_priority" });
  });

  it("names the ticket an action is for, returns a timed-out call and calls a specific ticket with a reason (FR-QUE-032, FR-AGT-011, FR-AGT-012)", async () => {
    const fetchImpl = vi.fn().mockImplementation(async () => json(200, {}));
    const client = new ApiClient({ apiOrigin: "", fetch: fetchImpl as unknown as typeof fetch });

    await client.sessions.serve("s1", 4, "t 2");
    await client.sessions.complete("s1", { note: "done" }, 5, "t2");
    await client.sessions.reannounce("s1", 1, "t2");
    await client.sessions.miss("s1", 1, "t2");
    await client.sessions.hold("s1", 2, "t2");
    await client.sessions.returnToQueue("s1", 1);
    await client.sessions.returnToQueue("s1", 1, "t2");
    await client.sessions.callTicket("s1", "t9", "Frail, asked to be seen");

    const calls = fetchImpl.mock.calls.map(([url, init]) => `${(init as RequestInit).method} ${String(url).replace("/api/v1", "")}`);
    expect(calls).toEqual([
      "POST /sessions/s1/serve?ticket_id=t%202",
      "POST /sessions/s1/complete?ticket_id=t2",
      "POST /sessions/s1/reannounce?ticket_id=t2",
      "POST /sessions/s1/miss?ticket_id=t2",
      "POST /sessions/s1/hold?ticket_id=t2",
      "POST /sessions/s1/return",
      "POST /sessions/s1/return?ticket_id=t2",
      "POST /sessions/s1/call",
    ]);
    const init = (index: number) => fetchImpl.mock.calls[index]?.[1] as RequestInit;
    expect((init(0).headers as Record<string, string>)["If-Match"]).toBe('"4"');
    expect((init(5).headers as Record<string, string>)["If-Match"]).toBe('"1"');
    expect(JSON.parse(String(init(1).body))).toEqual({ note: "done" });
    expect(JSON.parse(String(init(7).body))).toEqual({ ticket_id: "t9", reason: "Frail, asked to be seen" });
  });

  it("maps the counter session onto its paths and sends the ticket version as If-Match (FR-AGT-001, FR-QUE-031)", async () => {
    const fetchImpl = vi.fn().mockImplementation(async () => json(200, { items: [] }));
    const client = new ApiClient({ apiOrigin: "", fetch: fetchImpl as unknown as typeof fetch });

    await client.sessions.options();
    await client.sessions.current();
    await client.sessions.open({ counter_id: "c1", service_ids: ["v1"] });
    await client.sessions.next("s1");
    await client.sessions.reannounce("s1", 1);
    await client.sessions.miss("s1", 1);
    await client.sessions.miss("s1");
    await client.sessions.serve("s1", 1);
    await client.sessions.serve("s1");
    await client.sessions.complete("s1", { outcome_code_id: "o1", note: "done" }, 2);
    await client.sessions.close("s1");
    await client.sessions.hold("s1", 2);
    await client.sessions.hold("s1");
    await client.sessions.resume("s1", "t1", 3);
    await client.sessions.forceClose("s1", "stale tablet");
    await client.sessions.forceClose("s1");
    await client.sessions.transferTargets("s1");

    const calls = fetchImpl.mock.calls.map(([url, init]) => `${(init as RequestInit).method} ${String(url).replace("/api/v1", "")}`);
    expect(calls).toEqual([
      "GET /sessions/options",
      "GET /sessions/current",
      "POST /sessions",
      "POST /sessions/s1/next",
      "POST /sessions/s1/reannounce",
      "POST /sessions/s1/miss",
      "POST /sessions/s1/miss",
      "POST /sessions/s1/serve",
      "POST /sessions/s1/serve",
      "POST /sessions/s1/complete",
      "DELETE /sessions/s1",
      "POST /sessions/s1/hold",
      "POST /sessions/s1/hold",
      "POST /sessions/s1/hold",
      "POST /sessions/s1/force-close",
      "POST /sessions/s1/force-close",
      "GET /sessions/s1/transfer-targets",
    ]);
    const headers = (index: number) => (fetchImpl.mock.calls[index]?.[1] as RequestInit).headers as Record<string, string>;
    expect(JSON.parse(String((fetchImpl.mock.calls[2]?.[1] as RequestInit).body))).toEqual({ counter_id: "c1", service_ids: ["v1"] });
    expect(headers(4)["If-Match"]).toBe('"1"');
    expect(headers(5)["If-Match"]).toBe('"1"');
    expect(headers(6)["If-Match"]).toBeUndefined();
    expect(headers(7)["If-Match"]).toBe('"1"');
    expect(headers(8)["If-Match"]).toBeUndefined();
    expect(headers(9)["If-Match"]).toBe('"2"');
    expect(JSON.parse(String((fetchImpl.mock.calls[9]?.[1] as RequestInit).body))).toEqual({ outcome_code_id: "o1", note: "done" });
    expect(headers(11)["If-Match"]).toBe('"2"');
    expect(headers(12)["If-Match"]).toBeUndefined();
    expect((fetchImpl.mock.calls[12]?.[1] as RequestInit).body).toBeUndefined();
    expect(headers(13)["If-Match"]).toBe('"3"');
    expect(JSON.parse(String((fetchImpl.mock.calls[13]?.[1] as RequestInit).body))).toEqual({ ticket_id: "t1" });
    expect(JSON.parse(String((fetchImpl.mock.calls[14]?.[1] as RequestInit).body))).toEqual({ reason: "stale tablet" });
    expect((fetchImpl.mock.calls[15]?.[1] as RequestInit).body).toBeUndefined();
  });

  it("maps breaks, availability and the break report onto their paths (FR-AGT-020, FR-AGT-021, FR-AGT-022, FR-AGT-024, §20.4)", async () => {
    const fetchImpl = vi.fn().mockImplementation(async () => json(200, { items: [] }));
    const client = new ApiClient({ apiOrigin: "", fetch: fetchImpl as unknown as typeof fetch });

    await client.sessions.startBreak("s1", "b1");
    await client.sessions.endBreak("s1");
    await client.breaks.types();
    await client.breaks.createType({ name_i18n: { en: "Lunch" }, max_minutes: 30 });
    await client.breaks.updateType("b1", { name_i18n: { en: "Lunch break" }, max_minutes: null });
    await client.breaks.deactivateType("b1", "retired");
    await client.breaks.deactivateType("b1");
    await client.breaks.activateType("b1");
    await client.breaks.availability();
    await client.breaks.setAvailability("u1", { status: "on_break", break_type_id: "b1", reason: "outage" });
    await client.breaks.report({ from: "2026-09-19T00:00:00Z", agent_id: "u1", break_type_id: "" });
    await client.breaks.report();

    const calls = fetchImpl.mock.calls.map(([url, init]) => `${(init as RequestInit).method} ${String(url).replace("/api/v1", "")}`);
    expect(calls).toEqual([
      "POST /sessions/s1/break",
      "POST /sessions/s1/break",
      "GET /break-types",
      "POST /break-types",
      "PUT /break-types/b1",
      "POST /break-types/b1/deactivate",
      "POST /break-types/b1/deactivate",
      "POST /break-types/b1/activate",
      "GET /agents/availability",
      "PUT /agents/u1/availability",
      "GET /break-report?from=2026-09-19T00%3A00%3A00Z&agent_id=u1",
      "GET /break-report",
    ]);
    const body = (index: number) => JSON.parse(String((fetchImpl.mock.calls[index]?.[1] as RequestInit).body));
    expect(body(0)).toEqual({ break_type_id: "b1" });
    expect((fetchImpl.mock.calls[1]?.[1] as RequestInit).body).toBeUndefined();
    expect(body(3)).toEqual({ name_i18n: { en: "Lunch" }, max_minutes: 30 });
    expect(body(4)).toEqual({ name_i18n: { en: "Lunch break" }, max_minutes: null });
    expect(body(5)).toEqual({ reason: "retired" });
    expect((fetchImpl.mock.calls[6]?.[1] as RequestInit).body).toBeUndefined();
    expect(body(9)).toEqual({ status: "on_break", break_type_id: "b1", reason: "outage" });
  });

  it("maps a transfer onto POST /tickets/{id}/transfer with the note, the target and the ticket version as If-Match (FR-QUE-052)", async () => {
    const fetchImpl = vi.fn().mockImplementation(async () => json(200, { predecessor: {}, successor: {}, session: {} }));
    const client = new ApiClient({ apiOrigin: "", fetch: fetchImpl as unknown as typeof fetch });

    await client.tickets.transfer("t1", { service_id: "v2", agent_id: "u2", note: "Second opinion" }, 4);
    await client.tickets.transfer("t1", { service_id: "v2", note: "Lab" });

    const calls = fetchImpl.mock.calls.map(([url, init]) => `${(init as RequestInit).method} ${String(url).replace("/api/v1", "")}`);
    expect(calls).toEqual(["POST /tickets/t1/transfer", "POST /tickets/t1/transfer"]);
    const init = (index: number) => fetchImpl.mock.calls[index]?.[1] as RequestInit;
    expect(JSON.parse(String(init(0).body))).toEqual({ service_id: "v2", agent_id: "u2", note: "Second opinion" });
    expect((init(0).headers as Record<string, string>)["If-Match"]).toBe('"4"');
    expect((init(1).headers as Record<string, string>)["If-Match"]).toBeUndefined();
  });

  it("asks for a topic's snapshot on the polling path, the topic name escaped (FR-QUE-084)", async () => {
    const snapshot = { topic: "queue:v1", seq: 3, epoch: "e1", data: { waiting_count: 2 } };
    const fetchImpl = vi.fn().mockResolvedValue(json(200, snapshot));
    const client = new ApiClient({ apiOrigin: "", fetch: fetchImpl as unknown as typeof fetch, getAccessToken: () => "tok" });

    await expect(client.stream.snapshot("queue:v1")).resolves.toEqual(snapshot);

    expect(fetchImpl.mock.calls[0]?.[0]).toBe("/api/v1/stream/snapshot?topic=queue%3Av1");
    expect((fetchImpl.mock.calls[0]?.[1] as RequestInit).method).toBe("GET");
  });

  it("makes a new idempotency key each time", () => {
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
  });
});
