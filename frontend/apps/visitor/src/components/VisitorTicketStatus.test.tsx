import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderVisitor, stubApi } from "../test-utils";
import { VisitorTicketStatus } from "./VisitorTicketStatus";

const VIEW = {
  ticket_id: "t1",
  token_number: "A-042",
  state: "waiting",
  service_id: "v1",
  position: 3,
  estimated_wait_minutes: { low: 10, high: 15 },
  now_serving_token_number: "A-039",
  zone: { id: "z1", name: "Ground waiting", building_label: "Block A", floor_label: "Ground", wayfinding_image_url: "https://cdn.example.org/zone-a.png" },
  updated_at: "2026-09-20T10:00:00Z",
};

/** Forces the hook into its polling fallback in every test, so results never depend on jsdom's own WebSocket support. */
const NO_WEBSOCKET = {
  createSocket: () => {
    throw new Error("no WebSocket in tests");
  },
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("VisitorTicketStatus", () => {
  it("shows the token, live position, estimate range, now-serving token and where to wait", async () => {
    stubApi({ "GET /tickets/t1/visitor": () => json(200, VIEW) });

    renderVisitor(<VisitorTicketStatus ticketId="t1" credential="s3cr3t" streamDeps={NO_WEBSOCKET} />);

    expect(await screen.findByText("A-042")).toBeInTheDocument();
    expect(screen.getByText("You are number 3 in line")).toBeInTheDocument();
    expect(screen.getByText("Estimated wait: 10–15 minutes")).toBeInTheDocument();
    expect(screen.getByText("Now serving: A-039")).toBeInTheDocument();
    expect(screen.getByText("Floor: Ground")).toBeInTheDocument();
    expect(screen.getByText("Building: Block A")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "How to find your waiting area" })).toHaveAttribute("src", "https://cdn.example.org/zone-a.png");
  });

  it("labels the page as last-known-as-of, never as current, while it is only polling (FR-MOB-040)", async () => {
    stubApi({ "GET /tickets/t1/visitor": () => json(200, VIEW) });

    renderVisitor(<VisitorTicketStatus ticketId="t1" credential="s3cr3t" streamDeps={NO_WEBSOCKET} />);

    await screen.findByText(/Last known position as of/);
    expect(screen.queryByText("Live")).not.toBeInTheDocument();
  });

  it("shows a wrong or unknown ticket credential as an invalid link, not the staff sign-in message", async () => {
    stubApi({
      "GET /tickets/t1/visitor": () => json(401, { error: { code: "unauthenticated", message: "x", trace_id: "t" } }),
    });

    renderVisitor(<VisitorTicketStatus ticketId="t1" credential="wrong" streamDeps={NO_WEBSOCKET} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("This ticket link is invalid, or no longer belongs to an active ticket.");
  });

  it("offers to cancel while waiting, and hides the button once called", async () => {
    stubApi({ "GET /tickets/t1/visitor": () => json(200, { ...VIEW, state: "called" }) });

    renderVisitor(<VisitorTicketStatus ticketId="t1" credential="s3cr3t" streamDeps={NO_WEBSOCKET} />);

    await screen.findByText("You are being called");
    expect(screen.queryByRole("button", { name: "Cancel my ticket" })).not.toBeInTheDocument();
  });

  it("cancels the ticket after confirming, and shows it is done (FR-MOB-030)", async () => {
    vi.stubGlobal("confirm", vi.fn().mockReturnValue(true));
    stubApi({
      "GET /tickets/t1/visitor": () => json(200, VIEW),
      "POST /tickets/t1/visitor-cancel": () => json(200, { ...VIEW, state: "cancelled" }),
    });
    const user = userEvent.setup();

    renderVisitor(<VisitorTicketStatus ticketId="t1" credential="s3cr3t" streamDeps={NO_WEBSOCKET} />);

    const button = await screen.findByRole("button", { name: "Cancel my ticket" });
    await user.click(button);

    await waitFor(() => expect(screen.getByText("Your ticket has been cancelled.")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "Cancel my ticket" })).not.toBeInTheDocument();
  });

  it("does not cancel when the visitor does not confirm", async () => {
    vi.stubGlobal("confirm", vi.fn().mockReturnValue(false));
    const calls = stubApi({ "GET /tickets/t1/visitor": () => json(200, VIEW) });
    const user = userEvent.setup();

    renderVisitor(<VisitorTicketStatus ticketId="t1" credential="s3cr3t" streamDeps={NO_WEBSOCKET} />);

    const button = await screen.findByRole("button", { name: "Cancel my ticket" });
    await user.click(button);

    expect(calls.some((c) => c.path === "/tickets/t1/visitor-cancel")).toBe(false);
  });

  it("shows the specific refusal once a ticket has already been called by the time cancel is sent", async () => {
    vi.stubGlobal("confirm", vi.fn().mockReturnValue(true));
    stubApi({
      "GET /tickets/t1/visitor": () => json(200, VIEW),
      "POST /tickets/t1/visitor-cancel": () =>
        json(409, { error: { code: "conflict", message: "x", trace_id: "t", details: { reason: "ticket_already_called" } } }),
    });
    const user = userEvent.setup();

    renderVisitor(<VisitorTicketStatus ticketId="t1" credential="s3cr3t" streamDeps={NO_WEBSOCKET} />);

    const button = await screen.findByRole("button", { name: "Cancel my ticket" });
    await user.click(button);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This ticket can no longer be cancelled: it has already been called or closed.",
    );
  });

  it("opts out of notifications and shows it took effect (FR-NTF-035)", async () => {
    const calls = stubApi({
      "GET /tickets/t1/visitor": () => json(200, VIEW),
      "POST /tickets/t1/visitor/notification-opt-out": () => json(200, { opted_out: true }),
    });
    const user = userEvent.setup();

    renderVisitor(<VisitorTicketStatus ticketId="t1" credential="s3cr3t" streamDeps={NO_WEBSOCKET} />);

    const button = await screen.findByRole("button", { name: "Opt out of notifications" });
    await user.click(button);

    await waitFor(() => expect(screen.getByRole("button", { name: "Opt back in to notifications" })).toBeInTheDocument());
    const call = calls.find((c) => c.path === "/tickets/t1/visitor/notification-opt-out");
    expect(JSON.parse(String(call?.init.body))).toEqual({ opted_out: true, consent_text_version: undefined });
  });
});
