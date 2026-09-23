import { ThemeScript } from "@qms/ui";
import type { Metadata } from "next";
import type { ReactNode } from "react";
import { AdminChrome } from "../components/AdminChrome";
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
        <Providers>
          <AdminChrome>{children}</AdminChrome>
        </Providers>
      </body>
    </html>
  );
}
