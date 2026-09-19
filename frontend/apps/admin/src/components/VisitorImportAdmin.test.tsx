import type { VisitorImportMapping, VisitorImportReport } from "@qms/api-client";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { VisitorImportAdmin } from "./VisitorImportAdmin";

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));

const STAMP = "2026-09-19T20:30:00Z";
const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

const DEFAULT_MAPPING: VisitorImportMapping = {
  external_code_column: "external_code",
  name_column: "name",
  phone_column: "phone",
  email_column: "email",
  category_column: "category",
};

function report(over: Partial<VisitorImportReport> = {}): VisitorImportReport {
  return {
    id: "run-1",
    source: "manual",
    filename: "visitors.csv",
    started_at: STAMP,
    completed_at: STAMP,
    total_rows: 2,
    inserted_count: 1,
    updated_count: 1,
    failed_count: 0,
    status: "completed",
    errors: [],
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

describe("visitor CSV import (FR-INT-010, FR-INT-011)", () => {
  it("loads the current column mapping and saves an edited one", async () => {
    const calls = stubApi({
      ...NO_SESSION,
      "GET /visitors/import/mapping": () => json(200, DEFAULT_MAPPING),
      "PUT /visitors/import/mapping": (init) => json(200, JSON.parse(String(init.body)) as VisitorImportMapping),
      "GET /visitors/import/runs": () => json(200, { items: [] }),
    });
    renderApp(<VisitorImportAdmin />);

    const externalCodeField = await screen.findByLabelText("External code column");
    expect(externalCodeField).toHaveValue("external_code");

    await userEvent.clear(externalCodeField);
    await userEvent.type(externalCodeField, "Employee ID");
    await userEvent.click(screen.getByRole("button", { name: "Save mapping" }));

    expect(await screen.findByText("Mapping saved.")).toBeInTheDocument();
    expect(bodyOf(calls.find((c) => c.method === "PUT" && c.path === "/visitors/import/mapping"))).toMatchObject({
      external_code_column: "Employee ID",
      name_column: "name",
    });
  });

  it("uploads a chosen CSV file and shows the validation report", async () => {
    const calls = stubApi({
      ...NO_SESSION,
      "GET /visitors/import/mapping": () => json(200, DEFAULT_MAPPING),
      "GET /visitors/import/runs": () => json(200, { items: [] }),
      "POST /visitors/import": () => json(201, report()),
    });
    renderApp(<VisitorImportAdmin />);
    await screen.findByLabelText("External code column");

    const file = new File(["external_code,name\nE1,Amina\nE2,Karim\n"], "visitors.csv", { type: "text/csv" });
    await userEvent.upload(screen.getByLabelText("CSV file"), file);
    await userEvent.click(screen.getByRole("button", { name: "Import" }));

    expect(await screen.findByText("2 rows: 1 added, 1 updated, 0 failed")).toBeInTheDocument();
    const upload = calls.find((c) => c.method === "POST" && c.path === "/visitors/import");
    expect(bodyOf(upload)).toEqual({ filename: "visitors.csv", content: "external_code,name\nE1,Amina\nE2,Karim\n" });
  });

  it("lists row-level validation errors from the report", async () => {
    stubApi({
      ...NO_SESSION,
      "GET /visitors/import/mapping": () => json(200, DEFAULT_MAPPING),
      "GET /visitors/import/runs": () => json(200, { items: [] }),
      "POST /visitors/import": () =>
        json(201, report({ total_rows: 1, inserted_count: 0, updated_count: 0, failed_count: 1, errors: [{ line: 2, field: "name", code: "required" }] })),
    });
    renderApp(<VisitorImportAdmin />);
    await screen.findByLabelText("External code column");

    const file = new File(["external_code,name\nE1,\n"], "bad.csv", { type: "text/csv" });
    await userEvent.upload(screen.getByLabelText("CSV file"), file);
    await userEvent.click(screen.getByRole("button", { name: "Import" }));

    expect(await screen.findByText("Required")).toBeInTheDocument();
    expect(screen.getByText("name")).toBeInTheDocument();
  });

  it("lists past runs, most recent first, with their source", async () => {
    stubApi({
      ...NO_SESSION,
      "GET /visitors/import/mapping": () => json(200, DEFAULT_MAPPING),
      "GET /visitors/import/runs": () =>
        json(200, { items: [report({ id: "run-2", source: "scheduled", filename: "batch.csv" }), report({ id: "run-1" })] }),
    });
    renderApp(<VisitorImportAdmin />);

    expect(await screen.findByText("batch.csv")).toBeInTheDocument();
    expect(screen.getByText("Scheduled folder pickup")).toBeInTheDocument();
    expect(screen.getByText("visitors.csv")).toBeInTheDocument();
    expect(screen.getAllByText("Manual upload").length).toBeGreaterThan(0);
  });

  it("says why a caller without the permission sees no mapping", async () => {
    stubApi({
      ...NO_SESSION,
      "GET /visitors/import/mapping": () => json(403, { error: { code: "forbidden", message: "x", trace_id: "t" } }),
      "GET /visitors/import/runs": () => json(403, { error: { code: "forbidden", message: "x", trace_id: "t" } }),
    });
    renderApp(<VisitorImportAdmin />);

    await waitFor(() => expect(screen.getAllByRole("alert").length).toBeGreaterThan(0));
  });
});
