import type { WebhookDeliveryWithAttempts } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { WebhookDeliveryLogCard } from "./WebhookDeliveryLogCard";

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));

const STAMP = "2026-09-19T20:30:00Z";
const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

function delivery(over: Partial<WebhookDeliveryWithAttempts["delivery"]> = {}): WebhookDeliveryWithAttempts {
  return {
    delivery: {
      id: "d1",
      event_id: "ev1",
      endpoint_id: "e1",
      event_type: "ticket.called",
      occurred_at: STAMP,
      data: { ticket_id: "t1" },
      status: "failed",
      attempt_count: 6,
      last_error: "http_500",
      created_at: STAMP,
      delivered_at: null,
      ...over,
    },
    attempts: [],
  };
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function fakeApi(state: { rows: WebhookDeliveryWithAttempts[] }, extra: Routes = {}): Recorded[] {
  return stubApi({
    ...NO_SESSION,
    "GET /webhook-deliveries": () => json(200, { items: state.rows }),
    "POST /webhook-deliveries/d1/replay": () => {
      state.rows = state.rows.map((r) => (r.delivery.id === "d1" ? delivery({ status: "queued" }) : r));
      return json(200, state.rows[0]);
    },
    ...extra,
  });
}

describe("webhook delivery log (FR-INT-021)", () => {
  it("searches and lists a delivery with its status and attempt count", async () => {
    fakeApi({ rows: [delivery()] });
    renderApp(<WebhookDeliveryLogCard />);

    await userEvent.click(screen.getByRole("button", { name: "Search" }));

    expect(await screen.findByText("ticket.called")).toBeInTheDocument();
    const row = within(screen.getByRole("list"));
    expect(row.getByText("Failed")).toBeInTheDocument();
    expect(row.getByText("6")).toBeInTheDocument();
  });

  it("says nothing matches", async () => {
    fakeApi({ rows: [] });
    renderApp(<WebhookDeliveryLogCard />);

    await userEvent.click(screen.getByRole("button", { name: "Search" }));

    expect(await screen.findByText("No deliveries match.")).toBeInTheDocument();
  });

  it("replays a delivery and reflects the queued status after re-searching", async () => {
    const calls = fakeApi({ rows: [delivery()] });
    renderApp(<WebhookDeliveryLogCard />);
    await userEvent.click(screen.getByRole("button", { name: "Search" }));
    await within(screen.getByRole("list")).findByText("Failed");

    await userEvent.click(screen.getByRole("button", { name: "Replay ticket.called" }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/webhook-deliveries/d1/replay")).toBe(true));
    await waitFor(() => expect(within(screen.getByRole("list")).getByText("Queued")).toBeInTheDocument());
  });
});
