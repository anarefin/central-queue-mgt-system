import { ThemeScript } from "@qms/ui";
import type { Metadata } from "next";
import type { ReactNode } from "react";
import { ConsoleChrome } from "../components/ConsoleChrome";
import "./global.css";
import { Providers } from "./providers";

export const metadata: Metadata = { title: "QMS — console" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <head>
        <ThemeScript />
      </head>
      <body>
        <Providers>
          <ConsoleChrome>{children}</ConsoleChrome>
        </Providers>
      </body>
    </html>
  );
}
