// The visitor ticket page's own service worker (FR-MOB-003, ADR-0011, ADR-0012, ticket 37): the minimum a mobile
// browser needs to consider the page installable. It intentionally caches nothing and never intercepts a fetch, since
// the queue position it shows must always be current, never served from a stale cache. Ticket 39 adds the `push` and
// `notificationclick` handlers below.

self.addEventListener("install", () => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});

// A registered fetch handler is part of what makes a page installable; this one always falls through to the network.
self.addEventListener("fetch", () => {});

// ---- Web Push (ticket 39, FR-INT-040, §14.1, §14.2) -------------------------------------------------------------
// Shows a notification for a call, miss, no-show or transfer even with the page closed or the phone locked. The push
// payload (decrypted by the browser itself, RFC 8291 — this code never sees ciphertext) never carries the ticket
// secret; on a tap this looks up the last ticket this device subscribed for in its own IndexedDB (written by
// `src/lib/webPush.ts` at subscribe time, read only here) so it can reopen that exact ticket rather than the app's
// bare root, without the secret ever crossing the network.

const DB_NAME = "qms-visitor";
const STORE_NAME = "ticket-reference";
const RECORD_KEY = "current";

self.addEventListener("push", (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    data = {};
  }
  const title = data.subject || "QMS";
  const options = {
    body: data.body || "",
    tag: data.notification_id || undefined,
    data: { trigger: data.trigger || null },
  };
  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(openTicket());
});

async function openTicket() {
  const url = await ticketUrl();
  const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  for (const client of windows) {
    if ("focus" in client) return client.focus();
  }
  return self.clients.openWindow(url);
}

async function ticketUrl() {
  const reference = await readTicketReference();
  if (!reference) return self.registration.scope;
  return `${self.registration.scope}?t=${encodeURIComponent(reference.ticketId)}#s=${encodeURIComponent(reference.credential)}`;
}

function readTicketReference() {
  return new Promise((resolve) => {
    let request;
    try {
      request = indexedDB.open(DB_NAME, 1);
    } catch {
      resolve(null);
      return;
    }
    request.onupgradeneeded = () => {
      request.result.createObjectStore(STORE_NAME);
    };
    request.onsuccess = () => {
      try {
        const tx = request.result.transaction(STORE_NAME, "readonly");
        const get = tx.objectStore(STORE_NAME).get(RECORD_KEY);
        get.onsuccess = () => resolve(get.result || null);
        get.onerror = () => resolve(null);
      } catch {
        resolve(null);
      }
    };
    request.onerror = () => resolve(null);
  });
}
