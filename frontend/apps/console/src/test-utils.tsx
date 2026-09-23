import { I18nProvider } from "@qms/i18n/react";
import { act, render } from "@testing-library/react";
import type { ReactElement } from "react";
import { vi } from "vitest";
import { AuthProvider } from "./lib/auth";
import { AppLabelsProvider } from "./lib/labels";
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

/**
 * The browser's WebSocket, driven by hand: the test decides when it opens, what the hub says and when the network drops.
 * {@link stubApi} installs it, so no test ever reaches for a real socket.
 */
export class FakeWebSocket {
  static all: FakeWebSocket[] = [];
  sent: Array<Record<string, unknown>> = [];
  closed = false;
  onopen: ((event: unknown) => void) | null = null;
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onclose: ((event: unknown) => void) | null = null;
  onerror: ((event: unknown) => void) | null = null;

  constructor(
    readonly url: string,
    readonly protocols: string[],
  ) {
    FakeWebSocket.all.push(this);
  }

  send(data: string): void {
    this.sent.push(JSON.parse(data) as Record<string, unknown>);
  }

  close(): void {
    this.closed = true;
  }

  open(): void {
    act(() => this.onopen?.({}));
  }

  say(frame: Record<string, unknown>): void {
    act(() => this.onmessage?.({ data: JSON.stringify(frame) }));
  }

  /** The network refuses the connection, as a proxy that does not pass WebSocket would. */
  fail(): void {
    act(() => {
      this.onerror?.({});
      this.onclose?.({});
    });
  }

  /** The topics this socket asked the hub for, in the order it asked. */
  subscribed(): string[] {
    return this.sent
      .filter((f) => f.frame === "subscribe")
      .flatMap((f) => (f.topics as Array<{ topic: string }>).map((t) => t.topic));
  }
}

/** Stubs global fetch: `/config.json` is served, `GET /branding/theme` defaults to an unbranded reply (every app
    calls it on start, ticket 62), and everything else is looked up as "METHOD /path" (no /api/v1) — override either
    default by registering the same route explicitly. Also stubs WebSocket. */
export function stubApi(routes: Routes): Recorded[] {
  FakeWebSocket.all = [];
  vi.stubGlobal("WebSocket", FakeWebSocket);
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
    <UserLanguageContext.Provider value={setUserLanguage}>
      <I18nProvider loadExtra={false} userLanguage={userLanguage}>
        <RuntimeProvider>
          <AuthProvider>
            <AppLabelsProvider>{children}</AppLabelsProvider>
          </AuthProvider>
        </RuntimeProvider>
      </I18nProvider>
    </UserLanguageContext.Provider>
  );
}

export function renderApp(ui: ReactElement, languages: string[] = ["en-US"]) {
  vi.spyOn(navigator, "languages", "get").mockReturnValue(languages);
  return render(<Shell>{ui}</Shell>);
}
