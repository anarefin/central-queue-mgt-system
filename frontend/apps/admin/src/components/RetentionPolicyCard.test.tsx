import type { RetentionPolicy } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { RetentionPolicyCard } from "./RetentionPolicyCard";

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

function policy(overrides: Partial<RetentionPolicy> = {}): RetentionPolicy {
  return {
    data_class: "ticket_detail",
    retention_months: 24,
    mode: "anonymize",
    updated_at: "2026-09-19T10:00:00Z",
    updated_by: null,
    ...overrides,
  };
}

const DEFAULT_POLICIES: RetentionPolicy[] = [
  policy(),
  policy({ data_class: "ticket_aggregate", retention_months: 84, mode: "purge" }),
  policy({ data_class: "audit", retention_months: 24, mode: "purge" }),
];

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function fakeApi(state: { policies: RetentionPolicy[] }, extra: Routes = {}): Recorded[] {
  return stubApi({
    ...NO_SESSION,
    "GET /retention/policies": () => json(200, { items: state.policies }),
    "PUT /retention/policies/ticket_detail": (init) => {
      const updated: RetentionPolicy = { ...state.policies[0]!, ...(JSON.parse(String(init.body)) as Partial<RetentionPolicy>) };
      state.policies = [updated, ...state.policies.slice(1)];
      return json(200, updated);
    },
    ...extra,
  });
}

describe("retention, purge and BI access (SRS §16.3, FR-RPT-021/022, FR-SEC-032/043, ticket 53)", () => {
  it("shows every data class's own policy", async () => {
    fakeApi({ policies: DEFAULT_POLICIES });
    renderApp(<RetentionPolicyCard />);

    const rows = await screen.findAllByRole("listitem");
    expect(rows).toHaveLength(3);
    expect(within(rows[0]!).getByText("Ticket detail")).toBeInTheDocument();
    expect(within(rows[1]!).getByText("Ticket aggregate")).toBeInTheDocument();
    expect(within(rows[2]!).getByText("Audit log")).toBeInTheDocument();
    // Only ticket_detail's own mode is editable (FR-RPT-021's "per the client's choice").
    expect(within(rows[0]!).getByLabelText("When retention passes")).toBeInTheDocument();
    expect(within(rows[1]!).queryByLabelText("When retention passes")).not.toBeInTheDocument();
    expect(within(rows[2]!).queryByLabelText("When retention passes")).not.toBeInTheDocument();
  });

  it("saves a changed retention period and mode for ticket_detail", async () => {
    const calls = fakeApi({ policies: DEFAULT_POLICIES });
    renderApp(<RetentionPolicyCard />);

    const row = within((await screen.findAllByRole("listitem"))[0]!);
    const monthsField = row.getByLabelText("Retention (months)");
    await userEvent.clear(monthsField);
    await userEvent.type(monthsField, "18");
    await userEvent.selectOptions(row.getByLabelText("When retention passes"), "Purge outright");
    await userEvent.click(row.getByRole("button", { name: "Save" }));

    await waitFor(() => {
      const put = calls.find((c) => c.method === "PUT" && c.path === "/retention/policies/ticket_detail");
      expect(put).toBeTruthy();
      expect(JSON.parse(String(put?.init.body))).toEqual({ retention_months: 18, mode: "purge" });
    });
  });

  it("disables save until something actually changed", async () => {
    fakeApi({ policies: DEFAULT_POLICIES });
    renderApp(<RetentionPolicyCard />);

    const row = within((await screen.findAllByRole("listitem"))[0]!);
    await row.findByLabelText("Retention (months)");
    expect(row.getByRole("button", { name: "Save" })).toBeDisabled();
  });
});
