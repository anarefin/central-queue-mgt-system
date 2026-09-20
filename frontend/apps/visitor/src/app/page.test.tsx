import { screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderVisitor, stubApi } from "../test-utils";
import Home from "./page";

function setSearch(search: string) {
  window.history.pushState({}, "", `/visitor/${search}`);
}

afterEach(() => {
  setSearch("");
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("the visitor page's own ticket-reference contract (ADR-0012, kiosk QR: /visitor/?t=<id>#s=<secret>)", () => {
  it("asks for the QR to be scanned again when the link carries no ticket reference", async () => {
    setSearch("");
    renderVisitor(<Home />);

    expect(await screen.findByRole("alert")).toHaveTextContent("This link is missing your ticket reference. Scan the QR code on your token again.");
  });

  it("asks again when the id is present but the secret (fragment) is missing", async () => {
    setSearch("?t=t1");
    renderVisitor(<Home />);

    expect(await screen.findByRole("alert")).toHaveTextContent("This link is missing your ticket reference. Scan the QR code on your token again.");
  });

  it("reads the ticket id from the query string and the secret from the URL fragment, then strips the fragment", async () => {
    setSearch("?t=t1#s=s3cr3t");
    // No WebSocket in this test environment: forces the deterministic polling path, the same as jsdom would anyway.
    vi.stubGlobal(
      "WebSocket",
      class {
        constructor() {
          throw new Error("no WebSocket in tests");
        }
      },
    );
    stubApi({
      "GET /tickets/t1/visitor": () =>
        new Response(
          JSON.stringify({
            ticket_id: "t1",
            token_number: "A-001",
            state: "waiting",
            service_id: "v1",
            position: 1,
            estimated_wait_minutes: null,
            now_serving_token_number: null,
            zone: null,
            updated_at: "2026-09-20T10:00:00Z",
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
    });

    renderVisitor(<Home />);

    expect(await screen.findByText("A-001")).toBeInTheDocument();
    // The secret never lingers in the visible URL, browser history or document.location (defense in depth).
    expect(window.location.hash).toBe("");
    expect(window.location.href).not.toContain("s3cr3t");
  });
});
