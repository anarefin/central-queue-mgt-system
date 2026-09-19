import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { INVALID_CREDENTIALS, json, renderApp, stubApi, TOKEN_INVALID, type Recorded } from "../test-utils";
import { LoginForm } from "./LoginForm";

const router = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const NOT_SIGNED_IN = { "POST /auth/refresh": () => json(401, TOKEN_INVALID) };
const HEALTH = { "GET /health/dependencies": () => json(200, { status: "up", dependencies: {} }) };

beforeEach(() => router.replace.mockReset());
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

async function signIn(username: string, password: string) {
  await userEvent.type(await screen.findByLabelText("Username"), username);
  await userEvent.type(screen.getByLabelText("Password"), password);
  await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
}

const loginCall = (calls: Recorded[]) => calls.find((c) => c.path === "/auth/login");

describe("LoginForm", () => {
  it("is a real login form: labelled fields, a masked password and autofill hints", async () => {
    stubApi({ ...NOT_SIGNED_IN, ...HEALTH });
    renderApp(<LoginForm />);

    const password = await screen.findByLabelText("Password");
    expect(password).toHaveAttribute("type", "password");
    expect(password).toHaveAttribute("autocomplete", "current-password");
    expect(screen.getByLabelText("Username")).toHaveAttribute("autocomplete", "username");
  });

  it("renders in Bangla when the device prefers it", async () => {
    stubApi({ ...NOT_SIGNED_IN, ...HEALTH });
    renderApp(<LoginForm />, ["bn-BD"]);

    expect(await screen.findByLabelText("ব্যবহারকারীর নাম")).toBeInTheDocument();
    expect(screen.getByLabelText("পাসওয়ার্ড")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "সাইন ইন" })).toBeInTheDocument();
  });

  it("signs in without a bearer token, keeps nothing in web storage and moves on", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    const calls = stubApi({
      ...NOT_SIGNED_IN,
      ...HEALTH,
      "POST /auth/login": () => json(200, { access_token: "secret-access", token_type: "Bearer", expires_in: 900 }),
      "GET /auth/me": () => json(200, { id: "1", username: "rahim", display_name: null, preferred_language: null, roles: [], sites: [], groups: [] }),
    });
    renderApp(<LoginForm />);

    await signIn("  rahim  ", "Correct-Horse-9");

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/"));
    const call = loginCall(calls)!;
    expect(JSON.parse(call.init.body as string)).toEqual({ username: "rahim", password: "Correct-Horse-9" });
    expect((call.init.headers as Record<string, string>).Authorization).toBeUndefined();
    expect(setItem).not.toHaveBeenCalled();
    expect(localStorage.length + sessionStorage.length).toBe(0);
    expect(document.cookie).toBe("");
  });

  it("shows a localised message for wrong credentials and stays on the page", async () => {
    stubApi({ ...NOT_SIGNED_IN, ...HEALTH, "POST /auth/login": () => json(401, INVALID_CREDENTIALS) });
    renderApp(<LoginForm />);

    await signIn("rahim", "wrong");

    expect(await screen.findByRole("alert")).toHaveTextContent("The username or password is incorrect.");
    expect(router.replace).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Username")).toHaveValue("rahim");
  });

  it("tells a locked-out user how long to wait", async () => {
    stubApi({
      ...NOT_SIGNED_IN,
      ...HEALTH,
      "POST /auth/login": () =>
        json(423, { error: { code: "account_locked", message: "x", trace_id: "t", details: { retry_after_seconds: 890 } } }),
    });
    renderApp(<LoginForm />);

    await signIn("rahim", "wrong");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("temporarily locked");
    expect(alert).toHaveTextContent("Try again in 15 minute(s).");
  });

  it("explains a network failure instead of showing a raw error", async () => {
    stubApi({
      ...NOT_SIGNED_IN,
      ...HEALTH,
      "POST /auth/login": () => {
        throw new TypeError("Failed to fetch");
      },
    });
    renderApp(<LoginForm />);

    await signIn("rahim", "x");

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not reach the server");
  });

  it("disables the button and says so while signing in", async () => {
    let release!: (response: Response) => void;
    stubApi({ ...NOT_SIGNED_IN, ...HEALTH, "POST /auth/login": () => new Promise<Response>((r) => (release = r)) });
    renderApp(<LoginForm />);

    await userEvent.type(await screen.findByLabelText("Username"), "rahim");
    await userEvent.type(screen.getByLabelText("Password"), "x");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    const busy = await screen.findByRole("button", { name: "Signing in…" });
    expect(busy).toBeDisabled();
    release(json(401, INVALID_CREDENTIALS));
    await screen.findByRole("alert");
  });
});
