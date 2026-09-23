import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./global.css";
import { Providers } from "./providers";

export const metadata: Metadata = { title: "QMS — kiosk" };

export default function RootLayout({ children }: { children: ReactNode }) {
  // No ThemeProvider or toggle (ticket 62): the kiosk stays on the light, high-contrast theme regardless of the
  // device's OS preference, so `data-theme="light"` is fixed here rather than read from storage.
  return (
    <html lang="en" data-theme="light">
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
