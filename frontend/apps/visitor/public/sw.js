// The visitor ticket page's own service worker (FR-MOB-003, ADR-0011, ADR-0012, ticket 37): the minimum a mobile
// browser needs to consider the page installable. It intentionally caches nothing and never intercepts a fetch, since
// the queue position it shows must always be current, never served from a stale cache. Ticket 39 extends this file
// with a `push` and `notificationclick` handler for Web Push, once the visitor can opt into it.

self.addEventListener("install", () => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});

// A registered fetch handler is part of what makes a page installable; this one always falls through to the network.
self.addEventListener("fetch", () => {});
