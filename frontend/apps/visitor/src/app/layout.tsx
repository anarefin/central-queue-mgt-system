import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import "@qms/ui/styles.css";
import { Providers } from "./providers";
import { ServiceWorkerRegistration } from "./ServiceWorkerRegistration";

// Installable PWA (FR-MOB-003, ADR-0011, ADR-0012): the manifest and viewport theme Next attaches to every page;
// the service worker itself is registered by ServiceWorkerRegistration, since that needs a browser, not a build step.
// `referrer: "no-referrer"` is defense in depth for the ticket secret (§20.2, FR-SEC-033): it already never rides a
// URL a browser would put in a Referer (it lives only in the fragment, stripped before any request), but a page this
// sensitive sends no Referer to anything it links or loads (an admin-configured wayfinding or branding image, ticket
// 27/37) regardless of what else on the page might one day carry something in a query string.
export const metadata: Metadata = { title: "QMS — visitor", manifest: "manifest.webmanifest", referrer: "no-referrer" };
export const viewport: Viewport = { themeColor: "#0b5fff" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <ServiceWorkerRegistration />
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
