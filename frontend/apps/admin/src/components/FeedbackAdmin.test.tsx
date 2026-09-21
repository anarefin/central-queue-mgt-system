import type { PendingFeedbackComment } from "@qms/api-client";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Routes } from "../test-utils";
import { FeedbackAdmin } from "./FeedbackAdmin";

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

function comment(over: Partial<PendingFeedbackComment>): PendingFeedbackComment {
  return { id: "f1", ticket_id: "t1", token_number: "A-042", rating: 4, comment: "Friendly and quick.", submitted_at: "2026-09-21T10:00:00Z", ...over };
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("post-service feedback approval (FR-MOB-033)", () => {
  it("lists a comment waiting for a decision, with its rating and token", async () => {
    stubApi({ ...NO_SESSION, "GET /feedback/pending-comments": () => json(200, { items: [comment({})] }) });
    renderApp(<FeedbackAdmin />);

    expect(await screen.findByText("A-042")).toBeInTheDocument();
    expect(screen.getByText(/Rating: 4\/5/)).toBeInTheDocument();
    expect(screen.getByText("Friendly and quick.")).toBeInTheDocument();
  });

  it("says there is nothing to review when the queue is empty", async () => {
    stubApi({ ...NO_SESSION, "GET /feedback/pending-comments": () => json(200, { items: [] }) });
    renderApp(<FeedbackAdmin />);

    expect(await screen.findByText("No comments are waiting for a decision.")).toBeInTheDocument();
  });

  it("approves a comment and it leaves the queue", async () => {
    const state = { items: [comment({})] };
    const calls = stubApi({
      ...NO_SESSION,
      "GET /feedback/pending-comments": () => json(200, { items: state.items }),
      "POST /feedback/f1/approve-comment": () => {
        state.items = [];
        return json(200, { id: "f1", ticket_id: "t1", comment_approved: true });
      },
    });
    const user = userEvent.setup();
    renderApp(<FeedbackAdmin />);

    await user.click(await screen.findByRole("button", { name: "Approve for the agent to see" }));

    await waitFor(() => expect(screen.getByText("No comments are waiting for a decision.")).toBeInTheDocument());
    expect(calls.some((c) => c.method === "POST" && c.path === "/feedback/f1/approve-comment")).toBe(true);
  });

  it("says why a caller without the permission sees nothing", async () => {
    stubApi({ ...NO_SESSION, "GET /feedback/pending-comments": () => json(403, { error: { code: "forbidden", message: "x", trace_id: "t" } }) });
    renderApp(<FeedbackAdmin />);

    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });

  it("shows the rating in Bangla digits", async () => {
    stubApi({ ...NO_SESSION, "GET /feedback/pending-comments": () => json(200, { items: [comment({})] }) });
    renderApp(<FeedbackAdmin />, ["bn-BD"]);

    expect(await screen.findByText(/৪\/৫/)).toBeInTheDocument();
  });
});
