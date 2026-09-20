import type { OrgBranding, PrintTemplate } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { BrandingAdmin } from "./BrandingAdmin";

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

function branding(over: Partial<OrgBranding> = {}): OrgBranding {
  return { org_name: "Main Campus", primary_color: "#0b5fff", logo_url: null, updated_at: null, updated_by: null, ...over };
}

function template(over: Partial<PrintTemplate> = {}): PrintTemplate {
  return {
    fields: ["token_number", "floor", "service_group", "visitor_code", "visitor_name", "visitor_category", "issue_time"],
    notice_line: null,
    updated_at: null,
    updated_by: null,
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

describe("branding and printed-token template (FR-CFG-030..032)", () => {
  it("loads the current branding and saves an edited organisation name and colour", async () => {
    const calls = stubApi({
      ...NO_SESSION,
      "GET /branding": () => json(200, branding()),
      "GET /print-template": () => json(200, template()),
      "PUT /branding": (init) => json(200, { ...branding(), ...(JSON.parse(String(init.body)) as object) }),
    });
    renderApp(<BrandingAdmin />);

    const orgNameField = await screen.findByLabelText("Organisation name");
    expect(orgNameField).toHaveValue("Main Campus");

    await userEvent.clear(orgNameField);
    await userEvent.type(orgNameField, "Northside Clinic");
    await userEvent.click(screen.getByRole("button", { name: "Save branding" }));

    expect(await screen.findByText("Branding saved.")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "PUT" && c.path === "/branding"))).toMatchObject({
      org_name: "Northside Clinic",
      primary_color: "#0b5fff",
    });
  });

  it("toggles a printed field and saves the template, including the notice line", async () => {
    stubApi({
      ...NO_SESSION,
      "GET /branding": () => json(200, branding()),
      "GET /print-template": () => json(200, template()),
      "PUT /print-template": (init) => json(200, { ...template(), ...(JSON.parse(String(init.body)) as object) }),
    });
    renderApp(<BrandingAdmin />);

    const qrCheckbox = await screen.findByLabelText("QR code");
    expect(qrCheckbox).not.toBeChecked();
    await userEvent.click(qrCheckbox);

    const noticeLine = screen.getByLabelText("Notice line text");
    await userEvent.type(noticeLine, "Please keep this token.");

    await userEvent.click(screen.getByRole("button", { name: "Save template" }));

    expect(await screen.findByText("Template saved.")).toBeInTheDocument();
  });

  it("previews only the enabled fields with sample data and never calls the ticket API", async () => {
    const calls = stubApi({
      ...NO_SESSION,
      "GET /branding": () => json(200, branding({ org_name: "Northside Clinic" })),
      "GET /print-template": () => json(200, template({ fields: ["token_number", "service", "qr_code"] })),
    });
    renderApp(<BrandingAdmin />);

    const preview = await screen.findByLabelText("Token preview");
    expect(within(preview).getByText("Northside Clinic")).toBeInTheDocument();
    expect(within(preview).getByText(/Token number: A-001/)).toBeInTheDocument();
    expect(within(preview).getByText(/Service: Consultation/)).toBeInTheDocument();
    // a field the template does not enable (floor) is not shown
    expect(within(preview).queryByText(/Floor:/)).not.toBeInTheDocument();

    expect(calls.some((c) => c.path.startsWith("/tickets"))).toBe(false);
  });

  it("test-prints the preview without issuing a real ticket", async () => {
    stubApi({
      ...NO_SESSION,
      "GET /branding": () => json(200, branding()),
      "GET /print-template": () => json(200, template()),
    });
    const printSpy = vi.fn();
    window.print = printSpy;
    renderApp(<BrandingAdmin />);

    await screen.findByText("Preview and test print");
    await userEvent.click(screen.getByRole("button", { name: "Test print" }));

    expect(printSpy).toHaveBeenCalledTimes(1);
  });

  it("says why a caller without the permission sees no branding", async () => {
    stubApi({
      ...NO_SESSION,
      "GET /branding": () => json(403, { error: { code: "forbidden", message: "x", trace_id: "t" } }),
      "GET /print-template": () => json(403, { error: { code: "forbidden", message: "x", trace_id: "t" } }),
    });
    renderApp(<BrandingAdmin />);

    await waitFor(() => expect(screen.getAllByRole("alert").length).toBeGreaterThan(0));
  });
});
