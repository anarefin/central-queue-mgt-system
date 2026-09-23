import { ThemeScript } from "@qms/ui";
import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./global.css";
import { Providers } from "./providers";

export const metadata: Metadata = { title: "QMS — admin" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <head>
        <ThemeScript />
      </head>
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
