import type { Metadata } from "next";
import type { ReactNode } from "react";
import "@qms/ui/styles.css";
import { Providers } from "./providers";

export const metadata: Metadata = { title: "QMS — visitor" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
