import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderVisitor, stubApi } from "../test-utils";
import { PushOptIn } from "./PushOptIn";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/** Stubs a browser that supports Web Push: `navigator.serviceWorker`, `window.PushManager` and `Notification`. */
function stubPushCapableBrowser({
  existingSubscription = null as { endpoint: string; keys: { p256dh: string; auth: string } } | null,
  permission = "granted" as NotificationPermission,
} = {}) {
  const subscribe = vi.fn().mockResolvedValue({
    toJSON: () => ({ endpoint: "https://push.example/new", keys: { p256dh: "p2", auth: "a2" } }),
  });
  const getSubscription = vi.fn().mockResolvedValue(existingSubscription ? { toJSON: () => existingSubscription } : null);
  vi.stubGlobal("navigator", {
    languages: ["en-US"],
    userAgent: "Mozilla/5.0 (Linux; Android 14)",
    platform: "Linux armv8l",
    maxTouchPoints: 5,
    serviceWorker: { ready: Promise.resolve({ pushManager: { getSubscription, subscribe } }) },
  });
  vi.stubGlobal("PushManager", class {});
  vi.stubGlobal("Notification", { requestPermission: () => Promise.resolve(permission) });
  return { subscribe, getSubscription };
}

describe("PushOptIn", () => {
  it("renders nothing when the browser has no Push API", () => {
    const { container } = renderVisitor(<PushOptIn ticketId="t1" credential="s3cr3t" />);
    expect(container).toBeEmptyDOMElement();
  });

  it("shows the iOS home-screen hint instead of a button when Safari has not been added to the Home Screen (FR-MOB-020)", () => {
    vi.stubGlobal("navigator", {
      languages: ["en-US"],
      userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15",
      platform: "iPhone",
      maxTouchPoints: 5,
    });
    vi.stubGlobal("matchMedia", () => ({ matches: false }));

    renderVisitor(<PushOptIn ticketId="t1" credential="s3cr3t" />);

    expect(
      screen.getByText(/add this page to your Home Screen first/),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("subscribes and stores the subscription against the ticket, then shows it is on", async () => {
    stubPushCapableBrowser();
    const calls = stubApi({
      "GET /notification-config/web-push-key": () => json(200, { public_key: "dGVzdA" }),
      "POST /tickets/t1/push-subscription": () => json(200, { subscribed: true }),
    });
    const user = userEvent.setup();

    renderVisitor(<PushOptIn ticketId="t1" credential="s3cr3t" />);

    const button = await screen.findByRole("button", { name: "Notify me when it's my turn" });
    await user.click(button);

    await waitFor(() => expect(screen.getByText(/Notifications are on for this ticket/)).toBeInTheDocument());
    const call = calls.find((c) => c.path === "/tickets/t1/push-subscription");
    expect(JSON.parse(String(call?.init.body))).toEqual({
      endpoint: "https://push.example/new",
      keys: { p256dh: "p2", auth: "a2" },
    });
    expect(call?.init.headers).toMatchObject({ "X-Ticket-Secret": "s3cr3t" });
  });

  it("reuses an existing subscription rather than creating a second one", async () => {
    const { subscribe } = stubPushCapableBrowser({ existingSubscription: { endpoint: "https://push.example/e", keys: { p256dh: "p", auth: "a" } } });
    stubApi({
      "GET /notification-config/web-push-key": () => json(200, { public_key: "dGVzdA" }),
      "POST /tickets/t1/push-subscription": () => json(200, { subscribed: true }),
    });
    const user = userEvent.setup();

    renderVisitor(<PushOptIn ticketId="t1" credential="s3cr3t" />);

    await user.click(await screen.findByRole("button", { name: "Notify me when it's my turn" }));

    await waitFor(() => expect(screen.getByText(/Notifications are on for this ticket/)).toBeInTheDocument());
    expect(subscribe).not.toHaveBeenCalled();
  });

  it("shows a specific message when the visitor denies the browser permission prompt", async () => {
    stubPushCapableBrowser({ permission: "denied" });
    stubApi({ "GET /notification-config/web-push-key": () => json(200, { public_key: "dGVzdA" }) });
    const user = userEvent.setup();

    renderVisitor(<PushOptIn ticketId="t1" credential="s3cr3t" />);

    await user.click(await screen.findByRole("button", { name: "Notify me when it's my turn" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Notifications are blocked for this site in your browser settings.");
  });
});
