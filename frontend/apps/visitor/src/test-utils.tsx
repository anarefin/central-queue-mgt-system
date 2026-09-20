import { I18nProvider } from "@qms/i18n/react";
import { render } from "@testing-library/react";
import type { ReactElement, ReactNode } from "react";
import { vi } from "vitest";
import { RuntimeProvider } from "./lib/runtime";

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

export type Routes = Record<string, (init: RequestInit) => Response | Promise<Response>>;

export interface Recorded {
  method: string;
  path: string;
  init: RequestInit;
}

/** Stubs global fetch: `/visitor/config.json` is served, everything else is looked up as "METHOD /path" (no /api/v1). */
export function stubApi(routes: Routes): Recorded[] {
  const calls: Recorded[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit = {}) => {
    if (url.endsWith("/config.json")) return json(200, { apiOrigin: "" });
    const path = url.replace(/^.*\/api\/v1/, "");
    const method = init.method ?? "GET";
    calls.push({ method, path, init });
    const route = routes[`${method} ${path}`];
    if (!route) throw new TypeError(`unrouted ${method} ${path}`);
    return route(init);
  });
  return calls;
}

function Shell({ children }: { children: ReactNode }) {
  return (
    <I18nProvider loadExtra={false}>
      <RuntimeProvider>{children}</RuntimeProvider>
    </I18nProvider>
  );
}

export function renderVisitor(ui: ReactElement, languages: string[] = ["en-US"]) {
  vi.spyOn(navigator, "languages", "get").mockReturnValue(languages);
  return render(<Shell>{ui}</Shell>);
}
