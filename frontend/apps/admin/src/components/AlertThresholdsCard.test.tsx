import type { AlertThreshold, Site } from "@qms/api-client";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { AlertThresholdsCard } from "./AlertThresholdsCard";

const NO_SESSION: Routes = { "POST /auth/refresh": () => json(401, { error: { code: "token_invalid", message: "x", trace_id: "t" } }) };

const SITE: Site = {
  id: "s1",
  name: "Main campus",
  code: "MAIN",
  timezone: "Asia/Dhaka",
  address: "1 Campus Road",
  default_language: "en",
  enabled_languages: ["en", "bn"],
  active: true,
  clinical_sensitivity: false,
  created_at: "2026-09-19T10:00:00Z",
  updated_at: "2026-09-19T10:00:00Z",
};

const SERVICE = { id: "v1", name_i18n: { en: "Consultation" }, service_group: { id: "g1", name_i18n: { en: "Outpatient" } }, token_prefix: "A", icon: null, display_order: 0, waiting_count: 0, estimated_wait_minutes: null };

function empty(): AlertThreshold {
  return {
    service_id: "v1",
    queue_length_max: null,
    longest_wait_minutes_max: null,
    idle_counters_with_queue_max: null,
    no_show_rate_percent_max: null,
    device_offline_minutes_max: null,
    group_window_minutes: null,
    escalation_delay_minutes: null,
    updated_at: null,
    updated_by: null,
  };
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

/** An in-memory API, so a save is visible on the next read the screen makes. */
function fakeApi(state: { threshold: AlertThreshold }, extra: Routes = {}): Recorded[] {
  return stubApi({
    ...NO_SESSION,
    "GET /sites/s1/services": () => json(200, { site_id: "s1", default_language: "en", items: [SERVICE] }),
    "GET /services/v1/alert-thresholds": () => json(200, state.threshold),
    "PUT /services/v1/alert-thresholds": (init) => {
      state.threshold = { ...state.threshold, ...(JSON.parse(String(init.body)) as Partial<AlertThreshold>) };
      return json(200, state.threshold);
    },
    ...extra,
  });
}

describe("a Service's own alert thresholds (SRS §15.4, FR-MON-020, ticket 47)", () => {
  it("shows every metric unmonitored until a Service is picked and configured", async () => {
    fakeApi({ threshold: empty() });
    renderApp(<AlertThresholdsCard site={SITE} />);

    await userEvent.selectOptions(await screen.findByLabelText("Service"), "Consultation");

    expect(await screen.findByLabelText("Queue length")).toHaveValue("");
    expect(screen.getByLabelText("Escalation delay (minutes)")).toHaveValue("");
  });

  it("saves the configured thresholds, leaving a blank field unmonitored", async () => {
    const calls = fakeApi({ threshold: empty() });
    renderApp(<AlertThresholdsCard site={SITE} />);
    await userEvent.selectOptions(await screen.findByLabelText("Service"), "Consultation");
    await screen.findByLabelText("Queue length");

    await userEvent.type(screen.getByLabelText("Queue length"), "10");
    await userEvent.type(screen.getByLabelText("Longest wait (minutes)"), "15");
    await userEvent.type(screen.getByLabelText("Escalation delay (minutes)"), "30");
    await userEvent.click(screen.getByRole("button", { name: "Save thresholds" }));

    await waitFor(() => expect(screen.getByText("Thresholds saved.")).toBeInTheDocument());
    const saved = calls.find((c) => c.method === "PUT" && c.path === "/services/v1/alert-thresholds");
    expect(JSON.parse(String(saved?.init.body))).toEqual({
      queue_length_max: 10,
      longest_wait_minutes_max: 15,
      idle_counters_with_queue_max: null,
      no_show_rate_percent_max: null,
      device_offline_minutes_max: null,
      group_window_minutes: null,
      escalation_delay_minutes: 30,
    });
  });

  it("re-reads the saved values already configured for a Service", async () => {
    fakeApi({ threshold: { ...empty(), queue_length_max: 25, device_offline_minutes_max: 20 } });
    renderApp(<AlertThresholdsCard site={SITE} />);

    await userEvent.selectOptions(await screen.findByLabelText("Service"), "Consultation");

    expect(await screen.findByLabelText("Queue length")).toHaveValue("25");
    expect(screen.getByLabelText("Device offline (minutes)")).toHaveValue("20");
  });
});
