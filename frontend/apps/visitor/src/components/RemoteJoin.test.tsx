import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderVisitor, stubApi } from "../test-utils";
import { RemoteJoin } from "./RemoteJoin";

const AUTHENTICATED_ROUTES = {
  "POST /auth/visitor/refresh": () => json(200, { access_token: "tok", token_type: "Bearer", expires_in: 900 }),
  "GET /auth/visitor/me": () => json(200, { id: "v1", email: "visitor@example.com" }),
};

const UNAUTHENTICATED_ROUTES = {
  "POST /auth/visitor/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }),
};

const POLICY_OPEN = {
  service_id: "s1",
  virtual_queue_enabled: true,
  max_distance_m: null,
  max_remote_share_pct: 40,
  join_window_minutes: 30,
  arrival_deadline_minutes: 15,
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("RemoteJoin (ticket 42, FR-MOB-010, FR-MOB-023)", () => {
  it("asks an unauthenticated visitor to sign in first", async () => {
    stubApi(UNAUTHENTICATED_ROUTES);

    renderVisitor(<RemoteJoin serviceId="s1" />);

    expect(await screen.findByText(/join this queue remotely/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /send code/i })).toBeInTheDocument();
  });

  it("shows the policy and the forfeit consequences before the visitor joins", async () => {
    stubApi({
      ...AUTHENTICATED_ROUTES,
      "GET /remote-join/s1": () => json(200, { ...POLICY_OPEN, arrival_deadline_minutes: 12 }),
    });

    renderVisitor(<RemoteJoin serviceId="s1" />);

    expect(await screen.findByText(/before you join/i)).toBeInTheDocument();
    expect(screen.getByText(/12 minutes/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /join the queue/i })).toBeInTheDocument();
  });

  it("refuses outright when the service's virtual-queue flag is off", async () => {
    stubApi({
      ...AUTHENTICATED_ROUTES,
      "GET /remote-join/s1": () => json(200, { ...POLICY_OPEN, virtual_queue_enabled: false }),
    });

    renderVisitor(<RemoteJoin serviceId="s1" />);

    expect(await screen.findByText(/does not offer remote join/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /join the queue/i })).not.toBeInTheDocument();
  });

  it("joins with an Idempotency-Key when the distance check is off, needing no device position", async () => {
    const calls = stubApi({
      ...AUTHENTICATED_ROUTES,
      "GET /remote-join/s1": () => json(200, POLICY_OPEN),
      "POST /remote-join/s1": () => json(201, { id: "t1", state: "remote", origin_channel: "mobile", secret: "s3cr3t" }),
    });
    const user = userEvent.setup();

    renderVisitor(<RemoteJoin serviceId="s1" />);
    await user.click(await screen.findByRole("button", { name: /join the queue/i }));

    await waitFor(() => expect(calls.some((c) => c.path === "/remote-join/s1" && c.method === "POST")).toBe(true));
    const join = calls.find((c) => c.path === "/remote-join/s1" && c.method === "POST");
    expect((join?.init.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
    expect(JSON.parse(String(join?.init.body))).toEqual({});
  });

  it("shows a specific message when the visitor is too far from the site", async () => {
    stubApi({
      ...AUTHENTICATED_ROUTES,
      "GET /remote-join/s1": () => json(200, POLICY_OPEN),
      "POST /remote-join/s1": () =>
        json(409, { error: { code: "conflict", message: "x", details: { reason: "too_far" }, trace_id: "t" } }),
    });
    const user = userEvent.setup();

    renderVisitor(<RemoteJoin serviceId="s1" />);
    await user.click(await screen.findByRole("button", { name: /join the queue/i }));

    expect(await screen.findByText(/too far from the site/i)).toBeInTheDocument();
  });
});
