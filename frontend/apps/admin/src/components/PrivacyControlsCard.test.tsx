import type { VisitorExport, VisitorFieldConfig } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { PrivacyControlsCard } from "./PrivacyControlsCard";

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

function field(overrides: Partial<VisitorFieldConfig> = {}): VisitorFieldConfig {
  return { surface: "capture", field: "email", visible: true, updated_at: "2026-09-19T10:00:00Z", updated_by: null, ...overrides };
}

const CAPTURE_FIELDS: VisitorFieldConfig[] = [
  field({ field: "email" }),
  field({ field: "category" }),
  field({ field: "purpose" }),
];
const KIOSK_FIELDS: VisitorFieldConfig[] = [
  field({ surface: "kiosk_confirmation", field: "name" }),
  field({ surface: "kiosk_confirmation", field: "category" }),
];

const EXPORT: VisitorExport = {
  id: "v1",
  external_code: "V-CODE1",
  name: "Amina Rahman",
  category: "vip",
  phone: "01700000010",
  email: "amina@example.com",
  preferred_language: null,
  created_at: "2026-09-19T10:00:00Z",
  anonymized_at: null,
  tickets: [
    {
      id: "t1",
      token_number: "A001",
      state: "closed",
      service_names: { en: "Consultation" },
      site_id: "s1",
      purpose_note: "Needs wheelchair access",
      issued_at: "2026-09-19T10:00:00Z",
      closed_at: "2026-09-19T10:30:00Z",
    },
  ],
  notification_consent: null,
  retention_consent: null,
};

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function fakeApi(state: { captureFields: VisitorFieldConfig[] }, extra: Routes = {}): Recorded[] {
  return stubApi({
    ...NO_SESSION,
    "GET /privacy/field-config/capture": () => json(200, { items: state.captureFields }),
    "GET /privacy/field-config/kiosk_confirmation": () => json(200, { items: KIOSK_FIELDS }),
    "PUT /privacy/field-config/capture/email": (init) => {
      const updated: VisitorFieldConfig = { ...state.captureFields[0]!, ...(JSON.parse(String(init.body)) as Partial<VisitorFieldConfig>) };
      state.captureFields = [updated, ...state.captureFields.slice(1)];
      return json(200, updated);
    },
    ...extra,
  });
}

describe("privacy controls (SRS §25.3-25.4, ticket 54)", () => {
  it("shows both configurable surfaces' own field sets", async () => {
    fakeApi({ captureFields: CAPTURE_FIELDS });
    renderApp(<PrivacyControlsCard />);

    expect(await screen.findByText("Captured at walk-in registration")).toBeInTheDocument();
    expect(screen.getByText("Shown on the kiosk confirmation screen")).toBeInTheDocument();
    expect(await screen.findByLabelText("Email")).toBeChecked();
    await waitFor(() => expect(screen.getAllByLabelText("Category")).toHaveLength(2)); // one per surface
  });

  it("turning a field off saves the change", async () => {
    const calls = fakeApi({ captureFields: CAPTURE_FIELDS });
    renderApp(<PrivacyControlsCard />);

    const emailToggle = await screen.findByLabelText("Email");
    await userEvent.click(emailToggle);

    await waitFor(() => {
      const put = calls.find((c) => c.method === "PUT" && c.path === "/privacy/field-config/capture/email");
      expect(put).toBeTruthy();
      expect(JSON.parse(String(put?.init.body))).toEqual({ visible: false });
    });
  });

  it("exports a visitor's data by id", async () => {
    fakeApi(
      { captureFields: CAPTURE_FIELDS },
      { "GET /visitors/v1/export": () => json(200, EXPORT) },
    );
    renderApp(<PrivacyControlsCard />);

    await userEvent.type(await screen.findByLabelText("Visitor ID"), "v1");
    await userEvent.click(screen.getByRole("button", { name: "Export" }));

    expect(await screen.findByText("Amina Rahman")).toBeInTheDocument();
    expect(screen.getByText("amina@example.com")).toBeInTheDocument();
  });

  it("anonymising a visitor asks for confirmation first", async () => {
    const calls = fakeApi(
      { captureFields: CAPTURE_FIELDS },
      { "POST /visitors/v1/anonymize": () => json(200, { id: "v1", anonymized_at: "2026-09-19T11:00:00Z", tickets_anonymized: 1 }) },
    );
    renderApp(<PrivacyControlsCard />);
    vi.stubGlobal("confirm", vi.fn(() => true));

    await userEvent.type(await screen.findByLabelText("Visitor ID"), "v1");
    await userEvent.click(screen.getByRole("button", { name: "Delete (anonymise)" }));

    await waitFor(() => {
      expect(calls.some((c) => c.method === "POST" && c.path === "/visitors/v1/anonymize")).toBe(true);
    });
    expect(await screen.findByText(/Anonymised at/)).toBeInTheDocument();
  });
});
