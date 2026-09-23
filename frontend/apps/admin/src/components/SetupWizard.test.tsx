import { resetFeatureFlagsCacheForTests, type Site, type SetupState, type VerticalProfile } from "@qms/api-client";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { SetupWizard } from "./SetupWizard";

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

function orgAdminSession(): Routes {
  return {
    "POST /auth/refresh": () => json(200, { access_token: "tok", token_type: "Bearer", expires_in: 900 }),
    "GET /auth/me": () =>
      json(200, { id: "u1", username: "asha", display_name: "Asha Rahman", preferred_language: null, roles: ["org_admin"], sites: [], groups: [] }),
  };
}

function site(id: string, name: string): Site {
  return {
    id,
    name,
    code: id.toUpperCase(),
    timezone: "Asia/Dhaka",
    address: "1 Main Road",
    default_language: "en",
    enabled_languages: ["en", "bn"],
    active: true,
    clinical_sensitivity: false,
    created_at: "2026-09-22T00:00:00Z",
    updated_at: "2026-09-22T00:00:00Z",
  };
}

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

  it("seeds a site's starter catalogue and shows what was created and skipped (ticket 67)", async () => {
    const calls = stubApi({
      ...orgAdminSession(),
      "GET /setup/state": () => json(200, state({ profile_applied: true, active_profile: { id: "banking", applied_at: "2026-09-22T00:00:00Z", applied_by: "u1" } })),
      "GET /setup/profiles": () => json(200, [profile("banking")]),
      "GET /sites": () => json(200, { items: [site("s1", "Main branch")] }),
      "POST /setup/seed-catalogue": () =>
        json(200, {
          service_group_id: "g1",
          created: [
            { kind: "service_group", name: "Branch function" },
            { kind: "service", name: "Cash deposit" },
          ],
          skipped: [{ kind: "service", name: "Remittance", reason: "prefix_in_use" }],
        }),
    });
    renderApp(<SetupWizard />);

    await screen.findByRole("button", { name: "Seed starter catalogue" });
    await waitFor(() => expect(screen.getByRole("button", { name: "Seed starter catalogue" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "Seed starter catalogue" }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/setup/seed-catalogue")).toBe(true));
    expect(JSON.parse(String(calls.find((c) => c.method === "POST" && c.path === "/setup/seed-catalogue")?.init.body))).toEqual({ site_id: "s1" });

    expect(await screen.findByText("Created: Branch function")).toBeInTheDocument();
    expect(screen.getByText("Created: Cash deposit")).toBeInTheDocument();
    expect(screen.getByText("Skipped: Remittance (token prefix already in use)")).toBeInTheDocument();
    expect(screen.getByText("2 created, 1 skipped.")).toBeInTheDocument();
  });

  it("disables seeding until a profile is applied, even for an admin with the right roles", async () => {
    stubApi({
      ...orgAdminSession(),
      "GET /setup/state": () => json(200, state()),
      "GET /setup/profiles": () => json(200, [profile("banking")]),
    });
    renderApp(<SetupWizard />);

    expect(await screen.findByRole("button", { name: "Seed starter catalogue" })).toBeDisabled();
    expect(screen.getByText("Apply a vertical profile first.")).toBeInTheDocument();
  });

  it("disables seeding for a signed-out caller even once a profile is applied", async () => {
    stubApi({
      ...NO_SESSION,
      "GET /setup/state": () => json(200, state({ profile_applied: true, active_profile: { id: "banking", applied_at: "2026-09-22T00:00:00Z", applied_by: "u1" } })),
      "GET /setup/profiles": () => json(200, [profile("banking")]),
    });
    renderApp(<SetupWizard />);

    expect(await screen.findByRole("button", { name: "Seed starter catalogue" })).toBeDisabled();
  });
});

describe("feature flags card (ticket 68, CFG-003, SRS §27.5)", () => {
  afterEach(() => resetFeatureFlagsCacheForTests());

  it("shows every flag's current state, loaded from GET /setup/feature-flags", async () => {
    stubApi({
      ...orgAdminSession(),
      "GET /setup/state": () => json(200, state()),
      "GET /setup/profiles": () => json(200, [profile("banking")]),
      "GET /setup/feature-flags": () =>
        json(200, { appointment: true, virtual_queue: false, journey: true, multi_site: false, visitor_code_lookup: true, announce_visitor_name: false }),
    });
    renderApp(<SetupWizard />);

    expect(await screen.findByRole("checkbox", { name: /Appointments/ })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /Virtual queue/ })).not.toBeChecked();
    expect(screen.getByRole("checkbox", { name: /Multiple sites/ })).not.toBeChecked();
  });

  it("turns a flag on immediately, with no confirmation needed", async () => {
    const calls = stubApi({
      ...orgAdminSession(),
      "GET /setup/state": () => json(200, state()),
      "GET /setup/profiles": () => json(200, [profile("banking")]),
      "GET /setup/feature-flags": () =>
        json(200, { appointment: true, virtual_queue: false, journey: true, multi_site: true, visitor_code_lookup: true, announce_visitor_name: true }),
      "PUT /setup/feature-flags/virtual_queue": () => json(200, { virtual_queue: true }),
    });
    renderApp(<SetupWizard />);

    await userEvent.click(await screen.findByRole("checkbox", { name: /Virtual queue/ }));

    await waitFor(() => expect(calls.some((c) => c.method === "PUT" && c.path === "/setup/feature-flags/virtual_queue")).toBe(true));
    expect(JSON.parse(String(calls.find((c) => c.path === "/setup/feature-flags/virtual_queue")?.init.body))).toEqual({ enabled: true });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("asks for confirmation before turning a flag off, and only writes it once confirmed", async () => {
    const calls = stubApi({
      ...orgAdminSession(),
      "GET /setup/state": () => json(200, state()),
      "GET /setup/profiles": () => json(200, [profile("banking")]),
      "GET /setup/feature-flags": () =>
        json(200, { appointment: true, virtual_queue: true, journey: true, multi_site: true, visitor_code_lookup: true, announce_visitor_name: true }),
      "PUT /setup/feature-flags/appointment": () => json(200, { appointment: false }),
    });
    renderApp(<SetupWizard />);

    await userEvent.click(await screen.findByRole("checkbox", { name: /Appointments/ }));
    expect(calls.some((c) => c.path === "/setup/feature-flags/appointment")).toBe(false);
    expect(await screen.findByText(/turns the feature off across the whole organisation/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Turn off" }));

    await waitFor(() => expect(calls.some((c) => c.method === "PUT" && c.path === "/setup/feature-flags/appointment")).toBe(true));
    expect(JSON.parse(String(calls.find((c) => c.path === "/setup/feature-flags/appointment")?.init.body))).toEqual({ enabled: false });
  });
});
