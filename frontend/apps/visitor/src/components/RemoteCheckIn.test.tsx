import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderVisitor, stubApi } from "../test-utils";
import { RemoteCheckIn } from "./RemoteCheckIn";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/** Stubs a browser that resolves the device's own coordinates (ticket 43, FR-MOB-021, FR-MOB-024). */
function stubGeolocation(coords: { latitude: number; longitude: number } | "denied") {
  vi.stubGlobal("navigator", {
    languages: ["en-US"],
    geolocation: {
      getCurrentPosition: (success: PositionCallback, error?: PositionErrorCallback) => {
        if (coords === "denied") {
          error?.({ code: 1, message: "denied" } as GeolocationPositionError);
        } else {
          success({ coords: { ...coords, accuracy: 10 } } as GeolocationPosition);
        }
      },
    },
  });
}

const VIEW = { ticket_id: "t1", token_number: "A-042", state: "remote", service_id: "v1", position: 3, estimated_wait_minutes: null, now_serving_token_number: null, zone: null, updated_at: "2026-09-20T10:00:00Z" };

describe("RemoteCheckIn", () => {
  it("checks in by geofence with the device's own coordinates", async () => {
    stubGeolocation({ latitude: 23.8103, longitude: 90.4125 });
    const calls = stubApi({ "POST /tickets/t1/check-in": () => json(200, { ...VIEW, state: "waiting" }) });
    const user = userEvent.setup();

    renderVisitor(<RemoteCheckIn ticketId="t1" credential="s3cr3t" qr={false} />);

    await user.click(await screen.findByRole("button", { name: "I'm here — check in" }));

    await waitFor(() => expect(calls.some((c) => c.path === "/tickets/t1/check-in")).toBe(true));
    const call = calls.find((c) => c.path === "/tickets/t1/check-in");
    expect(JSON.parse(String(call?.init.body))).toEqual({ method: "geofence", latitude: 23.8103, longitude: 90.4125 });
    expect(call?.init.headers).toMatchObject({ "X-Ticket-Secret": "s3cr3t" });
  });

  it("confirms by QR without asking for location, when opened from the site's own QR", async () => {
    const calls = stubApi({ "POST /tickets/t1/check-in": () => json(200, { ...VIEW, state: "waiting" }) });
    const user = userEvent.setup();

    renderVisitor(<RemoteCheckIn ticketId="t1" credential="s3cr3t" qr={true} />);

    await user.click(await screen.findByRole("button", { name: "Confirm check-in" }));

    const call = calls.find((c) => c.path === "/tickets/t1/check-in");
    expect(JSON.parse(String(call?.init.body))).toEqual({ method: "qr" });
  });

  it("shows a friendly message when the visitor is too far from the site", async () => {
    stubGeolocation({ latitude: 24.9, longitude: 90.9 });
    stubApi({
      "POST /tickets/t1/check-in": () =>
        json(409, { error: { code: "conflict", message: "x", trace_id: "t", details: { reason: "too_far" } } }),
    });
    const user = userEvent.setup();

    renderVisitor(<RemoteCheckIn ticketId="t1" credential="s3cr3t" qr={false} />);
    await user.click(await screen.findByRole("button", { name: "I'm here — check in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "You don't seem to be at the site yet. Move closer and try again, or scan the site's QR code instead.",
    );
  });

  it("shows a friendly message when the browser denies location, pointing at the QR fallback", async () => {
    stubGeolocation("denied");
    stubApi({});
    const user = userEvent.setup();

    renderVisitor(<RemoteCheckIn ticketId="t1" credential="s3cr3t" qr={false} />);
    await user.click(await screen.findByRole("button", { name: "I'm here — check in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't get your location. Please scan the site's QR code instead.");
  });

  it("delays the ticket once, after confirming, and hides the button afterwards (FR-MOB-031)", async () => {
    stubGeolocation({ latitude: 23.8103, longitude: 90.4125 });
    stubApi({ "POST /tickets/t1/delay": () => json(200, { ...VIEW, state: "remote" }) });
    const user = userEvent.setup();

    renderVisitor(<RemoteCheckIn ticketId="t1" credential="s3cr3t" qr={false} />);
    await user.click(await screen.findByRole("button", { name: "I'm not ready yet" }));
    await user.click(within(screen.getByRole("dialog", { hidden: true })).getByRole("button", { name: "Confirm" }));

    await waitFor(() => expect(screen.getByText("Your token has been moved back. You'll be called later.")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "I'm not ready yet" })).not.toBeInTheDocument();
  });

  it("does not delay when the visitor does not confirm", async () => {
    stubGeolocation({ latitude: 23.8103, longitude: 90.4125 });
    const calls = stubApi({});
    const user = userEvent.setup();

    renderVisitor(<RemoteCheckIn ticketId="t1" credential="s3cr3t" qr={false} />);
    await user.click(await screen.findByRole("button", { name: "I'm not ready yet" }));
    await user.click(within(screen.getByRole("dialog", { hidden: true })).getByRole("button", { name: "Cancel" }));

    expect(calls.some((c) => c.path === "/tickets/t1/delay")).toBe(false);
  });

  it("shows the specific refusal once the one delay has already been used", async () => {
    stubGeolocation({ latitude: 23.8103, longitude: 90.4125 });
    stubApi({
      "POST /tickets/t1/delay": () =>
        json(409, { error: { code: "conflict", message: "x", trace_id: "t", details: { reason: "delay_already_used" } } }),
    });
    const user = userEvent.setup();

    renderVisitor(<RemoteCheckIn ticketId="t1" credential="s3cr3t" qr={false} />);
    await user.click(await screen.findByRole("button", { name: "I'm not ready yet" }));
    await user.click(within(screen.getByRole("dialog", { hidden: true })).getByRole("button", { name: "Confirm" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("You've already used your one delay for this token.");
  });
});
