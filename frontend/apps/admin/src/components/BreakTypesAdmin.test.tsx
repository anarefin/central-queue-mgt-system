import type { BreakType, BreakTypeInput } from "@qms/api-client";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { BreakTypesAdmin } from "./BreakTypesAdmin";

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));

const STAMP = "2026-09-19T20:30:00Z";
const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

function type(over: Partial<BreakType>): BreakType {
  return { id: "b0", name_i18n: { en: "Lunch", bn: "দুপুরের খাবার" }, max_minutes: 30, active: true, created_at: STAMP, updated_at: STAMP, ...over };
}

const LUNCH = type({ id: "b1" });
const SYSTEM = type({ id: "b2", name_i18n: { en: "System issue" }, max_minutes: null });

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

/** An in-memory API, so a write is visible on the next read the screen makes. */
function fakeApi(state: { types: BreakType[] }, extra: Routes = {}): Recorded[] {
  return stubApi({
    ...NO_SESSION,
    "GET /break-types": () => json(200, { items: state.types }),
    "POST /break-types": (init) => {
      const created = type({ id: "b9", ...(JSON.parse(String(init.body)) as BreakTypeInput) } as Partial<BreakType>);
      state.types = [...state.types, created];
      return json(201, created);
    },
    "PUT /break-types/b1": (init) => {
      const updated = { ...LUNCH, ...(JSON.parse(String(init.body)) as BreakTypeInput) } as BreakType;
      state.types = state.types.map((b) => (b.id === "b1" ? updated : b));
      return json(200, updated);
    },
    "POST /break-types/b1/deactivate": () => {
      state.types = state.types.map((b) => (b.id === "b1" ? { ...b, active: false } : b));
      return json(200, state.types[0]);
    },
    "POST /break-types/b1/activate": () => {
      state.types = state.types.map((b) => (b.id === "b1" ? { ...b, active: true } : b));
      return json(200, state.types[0]);
    },
    ...extra,
  });
}

function bodyOf(call: Recorded | undefined): unknown {
  return JSON.parse(String(call?.init.body));
}

describe("break types (FR-AGT-020)", () => {
  it("lists each type with its longest duration in words, or says it has none", async () => {
    fakeApi({ types: [LUNCH, SYSTEM] });
    renderApp(<BreakTypesAdmin />);

    expect(await screen.findByText("Maximum duration: 30 min")).toBeInTheDocument();
    expect(screen.getByText("Maximum duration: no limit")).toBeInTheDocument();
    expect(screen.getByText("Lunch")).toBeInTheDocument();
    expect(screen.getByText("System issue")).toBeInTheDocument();
  });

  it("says agents cannot take a break until a type is added", async () => {
    fakeApi({ types: [] });
    renderApp(<BreakTypesAdmin />);

    expect(await screen.findByText("No break types yet. Agents cannot take a break until one is added.")).toBeInTheDocument();
  });

  it("shows the names in the reader's language, falling back to English", async () => {
    fakeApi({ types: [LUNCH, SYSTEM] });
    renderApp(<BreakTypesAdmin />, ["bn-BD"]);

    expect(await screen.findByText("দুপুরের খাবার")).toBeInTheDocument();
    expect(screen.getByText("System issue")).toBeInTheDocument();
    expect(screen.getByText("সর্বোচ্চ সময়: 30 মিনিট")).toBeInTheDocument();
  });

  it("creates a type with a name in each language and a maximum, and lists it", async () => {
    const calls = fakeApi({ types: [LUNCH] });
    renderApp(<BreakTypesAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Add break type" }));

    await userEvent.type(screen.getByLabelText("Name (English)"), "Prayer");
    await userEvent.type(screen.getByLabelText("Name (Bangla)"), "নামাজ");
    await userEvent.type(screen.getByLabelText("Maximum duration (minutes, optional)"), "15");
    await userEvent.click(screen.getByRole("button", { name: "Create break type" }));

    expect(await screen.findByText("Prayer")).toBeInTheDocument();
    expect(screen.getByText("Maximum duration: 15 min")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/break-types"))).toEqual({ name_i18n: { en: "Prayer", bn: "নামাজ" }, max_minutes: 15 });
  });

  it("sends no maximum when it is left blank, and names the field the API refuses", async () => {
    const calls = fakeApi(
      { types: [] },
      { "POST /break-types": () => json(400, { error: { code: "validation_failed", message: "x", trace_id: "t", details: { fields: [{ field: "max_minutes", code: "Range" }] } } }) },
    );
    renderApp(<BreakTypesAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Add break type" }));
    await userEvent.type(screen.getByLabelText("Name (English)"), "Nap");
    await userEvent.type(screen.getByLabelText("Maximum duration (minutes, optional)"), "5000");
    await userEvent.click(screen.getByRole("button", { name: "Create break type" }));

    expect(await screen.findByText(/Check these fields: Maximum duration \(minutes, optional\)/)).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "POST" && c.path === "/break-types"))).toMatchObject({ max_minutes: 5000 });
  });

  it("edits a type from its current values and replaces it as a whole, clearing the maximum", async () => {
    const calls = fakeApi({ types: [LUNCH] });
    renderApp(<BreakTypesAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Edit Lunch" }));

    expect(screen.getByLabelText("Maximum duration (minutes, optional)")).toHaveValue(30);
    expect(screen.getByLabelText("Name (Bangla)")).toHaveValue("দুপুরের খাবার");
    await userEvent.clear(screen.getByLabelText("Maximum duration (minutes, optional)"));
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText("Maximum duration: no limit")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "PUT" && c.path === "/break-types/b1"))).toEqual({
      name_i18n: { en: "Lunch", bn: "দুপুরের খাবার" },
      max_minutes: null,
    });
  });

  it("deactivates after confirmation, saying breaks already taken keep the type, and activates again", async () => {
    const calls = fakeApi({ types: [LUNCH] });
    renderApp(<BreakTypesAdmin />);

    await userEvent.click(await screen.findByRole("button", { name: "Deactivate Lunch" }));
    expect(screen.getByText(/Lunch will no longer be offered when an agent takes a break\. Breaks already taken keep it\./)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Confirm deactivation" }));

    const activate = await screen.findByRole("button", { name: "Activate Lunch" });
    expect(calls.some((c) => c.method === "POST" && c.path === "/break-types/b1/deactivate")).toBe(true);
    await userEvent.click(activate);
    await screen.findByRole("button", { name: "Deactivate Lunch" });
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/break-types/b1/activate")).toBe(true));
  });

  it("says why a caller without the permission sees nothing", async () => {
    fakeApi({ types: [] }, { "GET /break-types": () => json(403, { error: { code: "forbidden", message: "x", trace_id: "t" } }) });
    renderApp(<BreakTypesAdmin />);

    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });
});
