/**
 * Web Push opt-in for the visitor ticket page (ticket 39, FR-INT-040, §14.1, §18.3): browser feature detection, iOS's
 * own "add to Home Screen first" rule (FR-MOB-020), the VAPID key encoding a browser's `pushManager.subscribe()`
 * needs, and a small IndexedDB record of which ticket this device last subscribed for — read by the service worker
 * on `notificationclick` (`public/sw.js`) so a tap returns to the exact ticket, not just the app's bare root. Nothing
 * here ever puts the ticket secret in the push payload or on the wire to the push service; it only ever travels
 * between this page and this device's own IndexedDB.
 */

const DB_NAME = "qms-visitor";
const STORE_NAME = "ticket-reference";
const RECORD_KEY = "current";

export function pushSupported(): boolean {
  return typeof navigator !== "undefined" && "serviceWorker" in navigator && typeof window !== "undefined" && "PushManager" in window;
}

/** iOS Safari only fires Web Push for a page added to the Home Screen (FR-MOB-020); every other browser needs nothing extra. */
export function isIos(): boolean {
  if (typeof navigator === "undefined") return false;
  const ua = navigator.userAgent || "";
  const iPadOs13Plus = navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1;
  return /iPad|iPhone|iPod/.test(ua) || iPadOs13Plus;
}

export function isStandalone(): boolean {
  if (typeof window === "undefined") return false;
  const displayModeStandalone = typeof window.matchMedia === "function" && window.matchMedia("(display-mode: standalone)").matches;
  const iosStandalone = (window.navigator as Navigator & { standalone?: boolean }).standalone === true;
  return displayModeStandalone || iosStandalone;
}

/** A browser's `applicationServerKey` wants raw bytes, not the base64url string the API hands back. */
export function urlBase64ToUint8Array(base64Url: string): Uint8Array<ArrayBuffer> {
  const padding = "=".repeat((4 - (base64Url.length % 4)) % 4);
  const base64 = (base64Url + padding).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(base64);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

export type SubscribeFailure = "unsupported" | "permission_denied" | "error";

export class SubscribeError extends Error {
  readonly reason: SubscribeFailure;
  constructor(reason: SubscribeFailure) {
    super(reason);
    this.reason = reason;
  }
}

/** Requests notification permission and returns (creating if needed) this device's own push subscription. */
export async function subscribeToPush(vapidPublicKey: string): Promise<PushSubscriptionJSON> {
  if (!pushSupported()) throw new SubscribeError("unsupported");
  try {
    const registration = await navigator.serviceWorker.ready;
    const permission = await Notification.requestPermission();
    if (permission !== "granted") throw new SubscribeError("permission_denied");
    const existing = await registration.pushManager.getSubscription();
    const subscription =
      existing ??
      (await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(vapidPublicKey),
      }));
    return subscription.toJSON();
  } catch (cause) {
    if (cause instanceof SubscribeError) throw cause;
    throw new SubscribeError("error");
  }
}

/** Lets `public/sw.js` reopen this exact ticket on a notification tap, without ever sending the secret to the server. */
export async function rememberTicketReference(ticketId: string, credential: string): Promise<void> {
  if (typeof indexedDB === "undefined") return;
  try {
    const db = await openDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, "readwrite");
      tx.objectStore(STORE_NAME).put({ ticketId, credential, savedAt: Date.now() }, RECORD_KEY);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } catch {
    // Best-effort: worst case, a notification tap opens the app's own root instead of the exact ticket.
  }
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => {
      request.result.createObjectStore(STORE_NAME);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
