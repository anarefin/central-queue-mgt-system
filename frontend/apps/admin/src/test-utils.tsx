import { I18nProvider } from "@qms/i18n/react";
import { ThemeProvider } from "@qms/ui";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { vi } from "vitest";
import { AppLabelsProvider } from "./lib/labels";
import { AuthProvider } from "./lib/auth";
import { RuntimeProvider } from "./lib/runtime";
import { UserLanguageContext } from "./lib/user-language";
import { useState, type ReactNode } from "react";

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

export const INVALID_CREDENTIALS = { error: { code: "invalid_credentials", message: "x", trace_id: "t" } };
export const TOKEN_INVALID = { error: { code: "token_invalid", message: "x", trace_id: "t" } };

export type Routes = Record<string, (init: RequestInit) => Response | Promise<Response>>;

export interface Recorded {
  method: string;
  path: string;
  init: RequestInit;
}

/** Stubs global fetch: `/config.json` is served, `GET /branding/theme` defaults to an unbranded reply (every app calls
    it on start, ticket 62), `GET /labels?lang=...` defaults to no overrides (every screen just shows the pack's own
    default noun, ticket 69), and everything else is looked up as "METHOD /path" (no /api/v1) — override either
    default by registering the same route explicitly. Neither default is recorded in the returned call log, so an
    existing test's exact-call assertions are unaffected by either being wired in. */
export function stubApi(routes: Routes): Recorded[] {
  const calls: Recorded[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit = {}) => {
    if (url.endsWith("/config.json")) return json(200, { apiOrigin: "" });
    const path = url.replace(/^.*\/api\/v1/, "");
    const method = init.method ?? "GET";
    if (method === "GET" && path === "/branding/theme" && !routes["GET /branding/theme"]) {
      return json(200, { org_name: "QMS", primary_color: "#0b5fff", logo_url: null });
    }
    if (method === "GET" && path.startsWith("/labels") && !routes[`GET ${path}`]) {
      return json(200, {});
    }
    calls.push({ method, path, init });
    const route = routes[`${method} ${path}`];
    if (!route) throw new TypeError(`unrouted ${method} ${path}`);
    return route(init);
  });
  return calls;
}

function Shell({ children }: { children: ReactNode }) {
  const [userLanguage, setUserLanguage] = useState<string | null>(null);
  return (
    <ThemeProvider>
      <UserLanguageContext.Provider value={setUserLanguage}>
        <I18nProvider loadExtra={false} userLanguage={userLanguage}>
          <RuntimeProvider>
            <AuthProvider>
              <AppLabelsProvider>{children}</AppLabelsProvider>
            </AuthProvider>
          </RuntimeProvider>
        </I18nProvider>
      </UserLanguageContext.Provider>
    </ThemeProvider>
  );
}

export function renderApp(ui: ReactElement, languages: string[] = ["en-US"]) {
  vi.spyOn(navigator, "languages", "get").mockReturnValue(languages);
  return render(<Shell>{ui}</Shell>);
}
