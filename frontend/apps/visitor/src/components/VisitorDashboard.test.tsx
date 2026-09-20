import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderVisitor, stubApi } from "../test-utils";
import { VisitorDashboard } from "./VisitorDashboard";

const TICKETS = { items: [{ id: "t1", token_number: "A-001", state: "waiting", service_id: "s1", service_names: { en: "Consultation" }, site_id: "st1", site_name: "Main", issued_at: "2026-09-20T10:00:00Z" }] };
const APPOINTMENTS = {
  items: [
    {
      id: "a1",
      reference_code: "A-XXXX",
      service_id: "s1",
      service_names: { en: "Consultation" },
      site_id: "st1",
      site_name: "Main",
      date: "2026-09-25",
      start: "09:00",
      end: "09:30",
      state: "booked",
    },
  ],
};
const SITES = { items: [{ site_id: "st1", site_name: "Main" }] };

const BASE_ROUTES = {
  "POST /auth/visitor/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }),
  "GET /visitors/me/tickets": () => json(200, TICKETS),
  "GET /visitors/me/appointments": () => json(200, APPOINTMENTS),
  "GET /visitors/me/saved-sites": () => json(200, SITES),
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("VisitorDashboard", () => {
  it("shows the visitor's own active tickets, appointments and saved sites", async () => {
    stubApi(BASE_ROUTES);

    renderVisitor(<VisitorDashboard />);

    expect(await screen.findByText(/A-001/)).toBeInTheDocument();
    expect(screen.getByText(/A-XXXX/)).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Remove" })).toBeInTheDocument();
  });

  it("cancels an appointment and reloads the list", async () => {
    const calls = stubApi({
      ...BASE_ROUTES,
      "DELETE /appointments/a1": () => new Response(null, { status: 204 }),
    });
    const user = userEvent.setup();

    renderVisitor(<VisitorDashboard />);
    await screen.findByText(/A-XXXX/);

    await user.click(screen.getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(calls.some((c) => c.path === "/appointments/a1" && c.method === "DELETE")).toBe(true));
  });

  it("reschedules an appointment through the inline form", async () => {
    const calls = stubApi({
      ...BASE_ROUTES,
      "PATCH /appointments/a1": () =>
        json(200, { id: "a1", reference_code: "A-XXXX", service_id: "s1", date: "2026-09-26", start: "10:00", end: "10:30", state: "booked", source: "visitor", visitor_id: "v1", preferred_agent_id: null, purpose_note: null, language: null, priority_class_id: null }),
    });
    const user = userEvent.setup();

    renderVisitor(<VisitorDashboard />);
    await screen.findByText(/A-XXXX/);

    await user.click(screen.getByRole("button", { name: "Reschedule" }));
    await user.type(screen.getByLabelText("Date"), "2026-09-26");
    await user.type(screen.getByLabelText("Start time"), "10:00");
    await user.type(screen.getByLabelText("End time"), "10:30");
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => {
      const call = calls.find((c) => c.path === "/appointments/a1" && c.method === "PATCH");
      expect(call).toBeDefined();
      expect(JSON.parse(String(call?.init.body))).toEqual({ date: "2026-09-26", start: "10:00", end: "10:30" });
    });
  });

  it("removes a saved site", async () => {
    const calls = stubApi({
      ...BASE_ROUTES,
      "DELETE /visitors/me/saved-sites/st1": () => new Response(null, { status: 204 }),
    });
    const user = userEvent.setup();

    renderVisitor(<VisitorDashboard />);

    await user.click(await screen.findByRole("button", { name: "Remove" }));

    await waitFor(() => expect(calls.some((c) => c.path === "/visitors/me/saved-sites/st1" && c.method === "DELETE")).toBe(true));
  });
});
