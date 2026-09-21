import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderVisitor, stubApi } from "../test-utils";
import { FeedbackForm } from "./FeedbackForm";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function body(call: { init: RequestInit } | undefined): unknown {
  return JSON.parse(String(call?.init.body));
}

describe("FeedbackForm (FR-MOB-033)", () => {
  it("cannot be submitted before a rating is chosen", async () => {
    stubApi({});
    renderVisitor(<FeedbackForm ticketId="t1" credential="s3cr3t" />);

    expect(await screen.findByRole("button", { name: "Send feedback" })).toBeDisabled();
  });

  it("submits the chosen rating and comment, and thanks the visitor", async () => {
    const calls = stubApi({
      "POST /tickets/t1/feedback": () => json(200, { ticket_id: "t1", rating: 5, comment: "Very helpful", submitted_at: "2026-09-21T10:00:00Z" }),
    });
    const user = userEvent.setup();
    renderVisitor(<FeedbackForm ticketId="t1" credential="s3cr3t" />);

    await user.selectOptions(screen.getByLabelText("Rating"), "5");
    await user.type(screen.getByLabelText("Comment (optional)"), "Very helpful");
    await user.click(screen.getByRole("button", { name: "Send feedback" }));

    await waitFor(() => expect(screen.getByText("Thank you for your feedback.")).toBeInTheDocument());
    const call = calls.find((c) => c.path === "/tickets/t1/feedback");
    expect(body(call)).toEqual({ rating: 5, comment: "Very helpful" });
    expect((call?.init.headers as Record<string, string>)["X-Ticket-Secret"]).toBe("s3cr3t");
  });

  it("sends no comment when the box is left blank", async () => {
    const calls = stubApi({
      "POST /tickets/t1/feedback": () => json(200, { ticket_id: "t1", rating: 3, comment: null, submitted_at: "2026-09-21T10:00:00Z" }),
    });
    const user = userEvent.setup();
    renderVisitor(<FeedbackForm ticketId="t1" credential="s3cr3t" />);

    await user.selectOptions(screen.getByLabelText("Rating"), "3");
    await user.click(screen.getByRole("button", { name: "Send feedback" }));

    await waitFor(() => expect(screen.getByText("Thank you for your feedback.")).toBeInTheDocument());
    expect(body(calls.find((c) => c.path === "/tickets/t1/feedback"))).toEqual({ rating: 3, comment: undefined });
  });

  it("hides itself when the visitor declines, sending nothing", async () => {
    const calls = stubApi({});
    const user = userEvent.setup();
    renderVisitor(<FeedbackForm ticketId="t1" credential="s3cr3t" />);

    await user.click(screen.getByRole("button", { name: "Not now" }));

    expect(screen.queryByRole("button", { name: "Send feedback" })).not.toBeInTheDocument();
    expect(calls.some((c) => c.path === "/tickets/t1/feedback")).toBe(false);
  });

  it("treats an already-submitted refusal the same as success", async () => {
    stubApi({
      "POST /tickets/t1/feedback": () =>
        json(409, { error: { code: "conflict", message: "x", trace_id: "t", details: { reason: "feedback_already_submitted" } } }),
    });
    const user = userEvent.setup();
    renderVisitor(<FeedbackForm ticketId="t1" credential="s3cr3t" />);

    await user.selectOptions(screen.getByLabelText("Rating"), "4");
    await user.click(screen.getByRole("button", { name: "Send feedback" }));

    await waitFor(() => expect(screen.getByText("Thank you for your feedback.")).toBeInTheDocument());
  });

  it("shows a generic error for any other refusal", async () => {
    stubApi({
      "POST /tickets/t1/feedback": () => json(500, { error: { code: "internal_error", message: "x", trace_id: "t" } }),
    });
    const user = userEvent.setup();
    renderVisitor(<FeedbackForm ticketId="t1" credential="s3cr3t" />);

    await user.selectOptions(screen.getByLabelText("Rating"), "2");
    await user.click(screen.getByRole("button", { name: "Send feedback" }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.queryByText("Thank you for your feedback.")).not.toBeInTheDocument();
  });
});
