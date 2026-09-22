import type { SetupState, VerticalProfile } from "@qms/api-client";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { SetupWizard } from "./SetupWizard";

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

function profile(id: string): VerticalProfile {
  return {
    id,
    labels: { "entity.visitor": { en: "Customer", bn: "গ্রাহক" } },
    starter_services: [{ name_i18n: { en: "Cash deposit" }, token_prefix: "A" }],
    priority_classes: [],
    numbering_defaults: { sequence_start: 1, padding: 3, reset_boundary: "daily" },
    report_defaults: ["operational_summary"],
    kpi_thresholds: { max_wait_minutes: 15 },
    feature_flags: { appointment: true },
  };
}

function state(over: Partial<SetupState> = {}): SetupState {
  return {
    profile_applied: false,
    active_profile: null,
    org_and_sites: false,
    zones_and_counters: false,
    services_and_numbering: false,
    users_and_roles: false,
    devices_registered: false,
    test_token: { issued: false, printed: false, called: false, announced: false, ticket_id: null, token_number: null },
    go_live_ready: false,
    go_live_at: null,
    ...over,
  };
}

function bodyOf(call: Recorded | undefined): unknown {
  return JSON.parse(String(call?.init.body));
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("setup wizard (SRS §26.2, FR-OPS-010, ticket 56)", () => {
  it("lets an admin apply a shipped vertical profile on a fresh installation (CFG-002)", async () => {
    const calls = stubApi({
      ...NO_SESSION,
      "GET /setup/state": () => json(200, state()),
      "GET /setup/profiles": () => json(200, [profile("banking"), profile("healthcare")]),
      "POST /setup/profile": (init) => json(200, { id: JSON.parse(String(init.body)).profile_id, applied_at: "2026-09-22T00:00:00Z", applied_by: "u1" }),
    });
    renderApp(<SetupWizard />);

    await screen.findByRole("heading", { name: "Vertical profile" });
    await userEvent.click(screen.getByRole("button", { name: "Apply profile" }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/setup/profile")).toBe(true));
    expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/setup/profile"))).toEqual({ profile_id: "banking" });
  });

  it("shows every §26.2 step's status and links to the admin screen that completes it", async () => {
    stubApi({
      ...NO_SESSION,
      "GET /setup/state": () =>
        json(
          200,
          state({
            profile_applied: true,
            active_profile: { id: "banking", applied_at: "2026-09-22T00:00:00Z", applied_by: "u1" },
            org_and_sites: true,
            zones_and_counters: true,
          }),
        ),
      "GET /setup/profiles": () => json(200, [profile("banking")]),
    });
    renderApp(<SetupWizard />);

    expect(await screen.findByText("Active profile: Banking")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open service catalogue →" })).toHaveAttribute("href", "/catalogue");
    expect(screen.getByRole("link", { name: "Open devices →" })).toHaveAttribute("href", "/devices");
    // Not-yet-done steps still show as pending, so the checklist is honest about what's left.
    expect(screen.getAllByText("Not done yet").length).toBeGreaterThan(0);
  });

  it("issues the wizard's own test token and walks it through print confirmation", async () => {
    let issued = false;
    const calls = stubApi({
      ...NO_SESSION,
      "GET /setup/state": () =>
        json(
          200,
          state({
            profile_applied: true,
            active_profile: { id: "banking", applied_at: "2026-09-22T00:00:00Z", applied_by: "u1" },
            test_token: issued
              ? { issued: true, printed: false, called: false, announced: false, ticket_id: "t1", token_number: "A-001" }
              : { issued: false, printed: false, called: false, announced: false, ticket_id: null, token_number: null },
          }),
        ),
      "GET /setup/profiles": () => json(200, [profile("banking")]),
      "POST /setup/test-token": () => {
        issued = true;
        return json(201, { id: "t1", token_number: "A-001" });
      },
    });
    renderApp(<SetupWizard />);

    await userEvent.type(await screen.findByLabelText("Service"), "svc-1");
    await userEvent.click(screen.getByRole("button", { name: "Issue test token" }));

    await screen.findByText('Test token A-001 issued.');
    expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/setup/test-token"))).toEqual({ service_id: "svc-1" });
  });

  it("confirms the announcement independently of having been called (FR-OPS-010)", async () => {
    let announced = false;
    const calls = stubApi({
      ...NO_SESSION,
      "GET /setup/state": () =>
        json(
          200,
          state({
            profile_applied: true,
            active_profile: { id: "banking", applied_at: "2026-09-22T00:00:00Z", applied_by: "u1" },
            test_token: { issued: true, printed: true, called: true, announced, ticket_id: "t1", token_number: "A-001" },
          }),
        ),
      "GET /setup/profiles": () => json(200, [profile("banking")]),
      "POST /setup/test-token/t1/confirm-announce": () => {
        announced = true;
        return json(200, state({ test_token: { issued: true, printed: true, called: true, announced: true, ticket_id: "t1", token_number: "A-001" } }));
      },
    });
    renderApp(<SetupWizard />);

    const confirmButton = await screen.findByRole("button", { name: "Confirm it announced" });
    await userEvent.click(confirmButton);

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/setup/test-token/t1/confirm-announce")).toBe(true));
  });

  it("blocks go-live until every step is ready, and confirms it once it is", async () => {
    let live = false;
    const readyState = state({
      profile_applied: true,
      active_profile: { id: "banking", applied_at: "2026-09-22T00:00:00Z", applied_by: "u1" },
      org_and_sites: true,
      zones_and_counters: true,
      services_and_numbering: true,
      users_and_roles: true,
      devices_registered: true,
      test_token: { issued: true, printed: true, called: true, announced: true, ticket_id: "t1", token_number: "A-001" },
      go_live_ready: true,
    });
    stubApi({
      ...NO_SESSION,
      "GET /setup/state": () => json(200, live ? { ...readyState, go_live_at: "2026-09-22T09:30:00.000Z" } : readyState),
      "GET /setup/profiles": () => json(200, [profile("banking")]),
      "POST /setup/go-live": () => {
        live = true;
        return json(200, { go_live_at: "2026-09-22T09:30:00.000Z" });
      },
    });
    renderApp(<SetupWizard />);

    const goLiveButton = await screen.findByRole("button", { name: "Go live" });
    expect(goLiveButton).toBeEnabled();
    await userEvent.click(goLiveButton);

    await screen.findByText(/Live since/);
  });

  it("keeps go-live disabled while the wizard is not ready", async () => {
    stubApi({
      ...NO_SESSION,
      "GET /setup/state": () => json(200, state()),
      "GET /setup/profiles": () => json(200, [profile("banking")]),
    });
    renderApp(<SetupWizard />);

    expect(await screen.findByRole("button", { name: "Go live" })).toBeDisabled();
    expect(screen.getByText("Complete every step above before going live.")).toBeInTheDocument();
  });
});
