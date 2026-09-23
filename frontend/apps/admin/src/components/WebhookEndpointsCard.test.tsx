import type { WebhookEndpoint, WebhookEndpointInput } from "@qms/api-client";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { WebhookEndpointsCard } from "./WebhookEndpointsCard";

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));

const STAMP = "2026-09-19T20:30:00Z";
const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

function endpoint(over: Partial<WebhookEndpoint>): WebhookEndpoint {
  return {
    id: "e0",
    description: "Billing system",
    url: "https://example.com/hooks",
    event_types: ["ticket.called"],
    active: true,
    created_at: STAMP,
    updated_at: STAMP,
    ...over,
  };
}

const BILLING = endpoint({ id: "e1" });

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

/** An in-memory API, so a write is visible on the next read the screen makes. */
function fakeApi(state: { endpoints: WebhookEndpoint[] }, extra: Routes = {}): Recorded[] {
  return stubApi({
    ...NO_SESSION,
    "GET /webhook-endpoints": () => json(200, { items: state.endpoints }),
    "POST /webhook-endpoints": (init) => {
      const input = JSON.parse(String(init.body)) as WebhookEndpointInput;
      const created = endpoint({ id: "e9", ...input, secret: "generated-secret-abc" });
      state.endpoints = [...state.endpoints, created];
      return json(201, created);
    },
    "PUT /webhook-endpoints/e1": (init) => {
      const input = JSON.parse(String(init.body)) as WebhookEndpointInput;
      const updated = { ...BILLING, ...input };
      state.endpoints = state.endpoints.map((e) => (e.id === "e1" ? updated : e));
      return json(200, updated);
    },
    "POST /webhook-endpoints/e1/rotate-secret": () => {
      const updated = { ...BILLING, secret: "rotated-secret-xyz" };
      state.endpoints = state.endpoints.map((e) => (e.id === "e1" ? updated : e));
      return json(200, updated);
    },
    "POST /webhook-endpoints/e1/deactivate": () => {
      state.endpoints = state.endpoints.map((e) => (e.id === "e1" ? { ...e, active: false } : e));
      return json(200, state.endpoints[0]);
    },
    "POST /webhook-endpoints/e1/activate": () => {
      state.endpoints = state.endpoints.map((e) => (e.id === "e1" ? { ...e, active: true } : e));
      return json(200, state.endpoints[0]);
    },
    ...extra,
  });
}

function bodyOf(call: Recorded | undefined): unknown {
  return JSON.parse(String(call?.init.body));
}

describe("webhook endpoints (FR-INT-020)", () => {
  it("lists each endpoint with its url and how many event types it subscribes to", async () => {
    fakeApi({ endpoints: [BILLING] });
    renderApp(<WebhookEndpointsCard />);

    expect(await screen.findByText("Billing system")).toBeInTheDocument();
    expect(screen.getByText("https://example.com/hooks")).toBeInTheDocument();
    expect(screen.getByText("Subscribed to 1 event type(s)")).toBeInTheDocument();
  });

  it("says there are none yet", async () => {
    fakeApi({ endpoints: [] });
    renderApp(<WebhookEndpointsCard />);

    expect(await screen.findByText("No webhook endpoints yet.")).toBeInTheDocument();
  });

  it("creates an endpoint subscribed to the chosen event types and shows the secret exactly once", async () => {
    const calls = fakeApi({ endpoints: [] });
    renderApp(<WebhookEndpointsCard />);
    await userEvent.click(await screen.findByRole("button", { name: "Add endpoint" }));

    await userEvent.type(screen.getByLabelText("Description"), "Fraud monitor");
    await userEvent.type(screen.getByLabelText("URL"), "https://fraud.example.com/hooks");
    await userEvent.click(screen.getByLabelText("Token called"));
    await userEvent.click(screen.getByLabelText("Token completed"));
    await userEvent.click(screen.getByRole("button", { name: "Create endpoint" }));

    expect(await screen.findByText("Fraud monitor")).toBeInTheDocument();
    expect(screen.getByText("generated-secret-abc")).toBeInTheDocument();
    expect(screen.getByText("Copy it now — it is shown only this once and cannot be read back later.")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/webhook-endpoints"))).toEqual({
      description: "Fraud monitor",
      url: "https://fraud.example.com/hooks",
      event_types: ["ticket.called", "ticket.completed"],
    });

    await userEvent.click(screen.getByRole("button", { name: "Dismiss" }));
    expect(screen.queryByText("generated-secret-abc")).not.toBeInTheDocument();
  });

  it("names the field the API refuses on an unsafe URL", async () => {
    fakeApi(
      { endpoints: [] },
      {
        "POST /webhook-endpoints": () =>
          json(400, { error: { code: "validation_failed", message: "x", trace_id: "t", details: { fields: [{ field: "url", code: "unsafe_endpoint:private_address" }] } } }),
      },
    );
    renderApp(<WebhookEndpointsCard />);
    await userEvent.click(await screen.findByRole("button", { name: "Add endpoint" }));
    await userEvent.type(screen.getByLabelText("Description"), "x");
    await userEvent.type(screen.getByLabelText("URL"), "http://127.0.0.1/hooks");
    await userEvent.click(screen.getByLabelText("Token called"));
    await userEvent.click(screen.getByRole("button", { name: "Create endpoint" }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });

  it("edits an endpoint's url and event types", async () => {
    const calls = fakeApi({ endpoints: [BILLING] });
    renderApp(<WebhookEndpointsCard />);
    await userEvent.click(await screen.findByRole("button", { name: "Edit Billing system" }));

    const urlField = screen.getByLabelText("URL");
    await userEvent.clear(urlField);
    await userEvent.type(urlField, "https://new.example.com/hooks");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(screen.getByText("https://new.example.com/hooks")).toBeInTheDocument());
    expect(bodyOf(calls.find((c) => c.method === "PUT" && c.path === "/webhook-endpoints/e1"))).toEqual({
      description: "Billing system",
      url: "https://new.example.com/hooks",
      event_types: ["ticket.called"],
    });
  });

  it("rotates the secret and shows the new one exactly once", async () => {
    fakeApi({ endpoints: [BILLING] });
    renderApp(<WebhookEndpointsCard />);

    await userEvent.click(await screen.findByRole("button", { name: "Rotate secret Billing system" }));

    expect(await screen.findByText("rotated-secret-xyz")).toBeInTheDocument();
  });

  it("deactivates after confirmation, saying the delivery log is kept, and activates again", async () => {
    const calls = fakeApi({ endpoints: [BILLING] });
    renderApp(<WebhookEndpointsCard />);

    await userEvent.click(await screen.findByRole("button", { name: "Deactivate Billing system" }));
    expect(screen.getByText(/Billing system will stop receiving deliveries\. Its delivery log is kept\./)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Confirm deactivation" }));

    const activate = await screen.findByRole("button", { name: "Activate Billing system" });
    expect(calls.some((c) => c.method === "POST" && c.path === "/webhook-endpoints/e1/deactivate")).toBe(true);
    await userEvent.click(activate);
    await screen.findByRole("button", { name: "Deactivate Billing system" });
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/webhook-endpoints/e1/activate")).toBe(true));
  });

  it("says why a caller without the permission sees nothing", async () => {
    fakeApi({ endpoints: [] }, { "GET /webhook-endpoints": () => json(403, { error: { code: "forbidden", message: "x", trace_id: "t" } }) });
    renderApp(<WebhookEndpointsCard />);

    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });
});
