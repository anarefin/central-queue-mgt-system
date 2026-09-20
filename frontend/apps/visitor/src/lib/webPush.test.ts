import { afterEach, describe, expect, it, vi } from "vitest";
import { isIos, isStandalone, pushSupported, SubscribeError, subscribeToPush, urlBase64ToUint8Array } from "./webPush";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("urlBase64ToUint8Array", () => {
  it("decodes a base64url string (no padding, - and _) to the matching bytes", () => {
    // "dGVzdA" is the base64url (no padding) encoding of the ASCII bytes for "test".
    const bytes = urlBase64ToUint8Array("dGVzdA");
    expect(new TextDecoder().decode(bytes)).toBe("test");
  });

  it("handles - and _ (base64url's own substitutes for + and /)", () => {
    // Any 3-byte block whose base64 uses + or / would prove this; 0xfb 0xff 0xbf -> standard base64 "+/+/", base64url "-_-_".
    const bytes = urlBase64ToUint8Array("-_-_");
    expect(Array.from(bytes)).toEqual([0xfb, 0xff, 0xbf]);
  });
});

describe("isIos", () => {
  it("is true for an iPhone user agent", () => {
    vi.stubGlobal("navigator", { userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)", platform: "iPhone", maxTouchPoints: 5 });
    expect(isIos()).toBe(true);
  });

  it("is true for iPadOS 13+, which reports as MacIntel with touch points", () => {
    vi.stubGlobal("navigator", { userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15)", platform: "MacIntel", maxTouchPoints: 5 });
    expect(isIos()).toBe(true);
  });

  it("is false for Android", () => {
    vi.stubGlobal("navigator", { userAgent: "Mozilla/5.0 (Linux; Android 14)", platform: "Linux armv8l", maxTouchPoints: 5 });
    expect(isIos()).toBe(false);
  });
});

describe("isStandalone", () => {
  it("is true when the display-mode media query matches", () => {
    vi.stubGlobal("window", { matchMedia: () => ({ matches: true }), navigator: {} });
    expect(isStandalone()).toBe(true);
  });

  it("is true for iOS's own navigator.standalone even without matchMedia support", () => {
    vi.stubGlobal("window", { navigator: { standalone: true } });
    expect(isStandalone()).toBe(true);
  });

  it("is false for a plain browser tab", () => {
    vi.stubGlobal("window", { matchMedia: () => ({ matches: false }), navigator: {} });
    expect(isStandalone()).toBe(false);
  });
});

describe("pushSupported", () => {
  it("is false without both serviceWorker and PushManager", () => {
    expect(pushSupported()).toBe(false);
  });
});

describe("subscribeToPush", () => {
  it("throws unsupported when the browser has no Push API", async () => {
    await expect(subscribeToPush("key")).rejects.toMatchObject({ reason: "unsupported" });
  });

  it("throws permission_denied when the visitor refuses the browser prompt", async () => {
    vi.stubGlobal("navigator", {
      serviceWorker: { ready: Promise.resolve({ pushManager: { getSubscription: () => Promise.resolve(null) } }) },
    });
    vi.stubGlobal("PushManager", class {});
    vi.stubGlobal("Notification", { requestPermission: () => Promise.resolve("denied") });

    await expect(subscribeToPush("key")).rejects.toMatchObject({ reason: "permission_denied" });
  });

  it("reuses an existing subscription instead of creating a second one", async () => {
    const existing = { toJSON: () => ({ endpoint: "https://push.example/e", keys: { p256dh: "p", auth: "a" } }) };
    const subscribe = vi.fn();
    vi.stubGlobal("navigator", {
      serviceWorker: { ready: Promise.resolve({ pushManager: { getSubscription: () => Promise.resolve(existing), subscribe } }) },
    });
    vi.stubGlobal("PushManager", class {});
    vi.stubGlobal("Notification", { requestPermission: () => Promise.resolve("granted") });

    const result = await subscribeToPush("key");

    expect(result).toEqual({ endpoint: "https://push.example/e", keys: { p256dh: "p", auth: "a" } });
    expect(subscribe).not.toHaveBeenCalled();
  });

  it("subscribes with the given VAPID key when there is no existing subscription", async () => {
    const created = { toJSON: () => ({ endpoint: "https://push.example/new", keys: { p256dh: "p2", auth: "a2" } }) };
    const subscribe = vi.fn().mockResolvedValue(created);
    vi.stubGlobal("navigator", {
      serviceWorker: { ready: Promise.resolve({ pushManager: { getSubscription: () => Promise.resolve(null), subscribe } }) },
    });
    vi.stubGlobal("PushManager", class {});
    vi.stubGlobal("Notification", { requestPermission: () => Promise.resolve("granted") });

    const result = await subscribeToPush("dGVzdA");

    expect(result.endpoint).toBe("https://push.example/new");
    expect(subscribe).toHaveBeenCalledWith(expect.objectContaining({ userVisibleOnly: true }));
  });
});

describe("SubscribeError", () => {
  it("carries its reason", () => {
    expect(new SubscribeError("error").reason).toBe("error");
  });
});
