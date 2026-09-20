import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderVisitor, stubApi } from "../test-utils";
import { VisitorLogin } from "./VisitorLogin";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("VisitorLogin", () => {
  it("requests a code, then verifies it and signs the visitor in", async () => {
    const calls = stubApi({
      "POST /auth/visitor/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }),
      "POST /auth/visitor/otp/request": () => new Response(null, { status: 204 }),
      "POST /auth/visitor/otp/verify": () => json(200, { access_token: "a1", token_type: "Bearer", expires_in: 900 }),
      "GET /auth/visitor/me": () => json(200, { id: "v1", email: "visitor@example.com" }),
    });
    const user = userEvent.setup();

    renderVisitor(<VisitorLogin />);

    await user.type(screen.getByLabelText("Email address"), "visitor@example.com");
    await user.click(screen.getByRole("button", { name: "Send code" }));

    await screen.findByText("We sent a code to visitor@example.com. Enter it below.");
    const requestCall = calls.find((c) => c.path === "/auth/visitor/otp/request");
    expect(JSON.parse(String(requestCall?.init.body))).toEqual({ email: "visitor@example.com" });

    await user.type(screen.getByLabelText("One-time code"), "123456");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    await waitFor(() => {
      const verifyCall = calls.find((c) => c.path === "/auth/visitor/otp/verify");
      expect(JSON.parse(String(verifyCall?.init.body))).toEqual({ email: "visitor@example.com", code: "123456" });
    });
  });

  it("shows a rate-limit error with a retry hint when too many codes were requested", async () => {
    stubApi({
      "POST /auth/visitor/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }),
      "POST /auth/visitor/otp/request": () =>
        json(429, { error: { code: "rate_limited", message: "x", trace_id: "t", details: { retry_after_seconds: 120 } } }),
    });
    const user = userEvent.setup();

    renderVisitor(<VisitorLogin />);
    await user.type(screen.getByLabelText("Email address"), "visitor@example.com");
    await user.click(screen.getByRole("button", { name: "Send code" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Too many requests. Please try again shortly.");
  });

  it("shows the wrong-code message and lets the visitor go back to change the email", async () => {
    const calls = stubApi({
      "POST /auth/visitor/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }),
      "POST /auth/visitor/otp/request": () => new Response(null, { status: 204 }),
      "POST /auth/visitor/otp/verify": () => json(401, { error: { code: "invalid_credentials", message: "x", trace_id: "t" } }),
    });
    const user = userEvent.setup();

    renderVisitor(<VisitorLogin />);
    await user.type(screen.getByLabelText("Email address"), "visitor@example.com");
    await user.click(screen.getByRole("button", { name: "Send code" }));
    await screen.findByLabelText("One-time code");
    await user.type(screen.getByLabelText("One-time code"), "000000");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("That code is incorrect or has expired. Please request a new one.");

    await user.click(screen.getByRole("button", { name: "Use a different email" }));
    expect(screen.getByLabelText("Email address")).toBeInTheDocument();
    expect(calls.filter((c) => c.path === "/auth/visitor/otp/request")).toHaveLength(1);
  });
});
