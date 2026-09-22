import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Routes } from "../test-utils";
import { DiagnosticsPanel } from "./DiagnosticsPanel";

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

function fileResponse(content: string, contentType: string, filename: string): Response {
  return new Response(content, { status: 200, headers: { "Content-Type": contentType, "Content-Disposition": `attachment; filename="${filename}"` } });
}

let clicked: string[];

beforeEach(() => {
  clicked = [];
  // jsdom does not implement the Blob-URL API at all; downloadBlob() needs both.
  URL.createObjectURL = vi.fn(() => "blob:mock");
  URL.revokeObjectURL = vi.fn();
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
    clicked.push(this.download);
  });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("DiagnosticsPanel (ticket 60, FR-OPS-040)", () => {
  it("downloads the diagnostics bundle on demand", async () => {
    stubApi({ ...NO_SESSION, "GET /ops/diagnostics": () => fileResponse("zip-bytes", "application/zip", "qms-diagnostics-20260101-000000.zip") });
    renderApp(<DiagnosticsPanel />);

    await userEvent.click(screen.getByRole("button", { name: "Download diagnostics bundle" }));

    await waitFor(() => expect(clicked).toContain("qms-diagnostics-20260101-000000.zip"));
  });

  it("shows a localised error when the download fails", async () => {
    stubApi({
      ...NO_SESSION,
      "GET /ops/diagnostics": () => json(403, { error: { code: "forbidden", message: "x", trace_id: "t" } }),
    });
    renderApp(<DiagnosticsPanel />);

    await userEvent.click(screen.getByRole("button", { name: "Download diagnostics bundle" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not build the diagnostics bundle.");
    expect(alert.textContent).not.toMatch(/errors\./);
  });
});
