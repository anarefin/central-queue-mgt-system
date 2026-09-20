"use client";

import { useEffect } from "react";
import { BASE_PATH } from "../lib/runtime";

/** Registers the visitor page's own service worker (FR-MOB-003, ADR-0011): installability, and the `push`/`notificationclick`
 * handlers `public/sw.js` adds for Web Push (ticket 39) once a visitor opts in through {@code PushOptIn}. */
export function ServiceWorkerRegistration() {
  useEffect(() => {
    if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
    navigator.serviceWorker.register(`${BASE_PATH}/sw.js`).catch(() => {
      // Installability is a progressive enhancement; the ticket page works over plain HTTP(S) without it.
    });
  }, []);
  return null;
}
