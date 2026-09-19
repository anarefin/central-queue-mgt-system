import { I18nProvider } from "@qms/i18n/react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RuntimeProvider } from "../lib/runtime";
import { HealthPanel } from "./HealthPanel";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const HEALTHY = {
  status: "up",
  dependencies: {
    database: { status: "up" },
    realtime_hub: { status: "not_configured" },
    notification_gateway: { status: "not_configured" },
  },
};

/** Runs the real RuntimeProvider: config.json is served by the stub, everything else goes to `api`. */
function renderWith(api: typeof fetch, languages: string[] = ["en-US"]) {
  vi.spyOn(navigator, "languages", "get").mockReturnValue(languages);
  vi.stubGlobal("fetch", (url: string, init?: RequestInit) =>
    url.endsWith("/config.json") ? Promise.resolve(json(200, { apiOrigin: "" })) : api(url, init),
  );
  return render(
    <I18nProvider loadExtra={false}>
      <RuntimeProvider>
        <HealthPanel />
      </RuntimeProvider>
    </I18nProvider>,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("HealthPanel", () => {
  it("shows backend and dependency health fetched through the api-client", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(json(200, HEALTHY));
    renderWith(fetchImpl as unknown as typeof fetch);

    expect(await screen.findByText("Backend")).toBeInTheDocument();
    expect(screen.getByText("Database")).toBeInTheDocument();
    expect(screen.getAllByText("Up")).toHaveLength(2); // backend + database
    expect(screen.getAllByText("Not configured")).toHaveLength(2); // hub + gateway
    expect(fetchImpl).toHaveBeenCalledWith("/api/v1/health/dependencies", expect.anything());
  });

  it("renders in Bangla when the device prefers it", async () => {
    renderWith(vi.fn().mockResolvedValue(json(200, HEALTHY)) as unknown as typeof fetch, ["bn-BD"]);

    expect(await screen.findByText("সিস্টেমের অবস্থা")).toBeInTheDocument();
    expect(screen.queryByText("System health")).not.toBeInTheDocument();
    expect(document.documentElement.lang).toBe("bn");
  });

  it("explains an unreachable backend with a localised message, not a raw code", async () => {
    renderWith(vi.fn().mockRejectedValue(new TypeError("offline")) as unknown as typeof fetch);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The backend cannot be reached.");
    expect(alert).toHaveTextContent("Could not reach the server");
    expect(alert.textContent).not.toMatch(/errors\./);
  });

  it("reports a down database from the API", async () => {
    const down = { ...HEALTHY, status: "down", dependencies: { ...HEALTHY.dependencies, database: { status: "down" } } };
    renderWith(vi.fn().mockResolvedValue(json(200, down)) as unknown as typeof fetch);

    expect(await screen.findAllByText("Down")).toHaveLength(2);
  });

  it("retries on demand", async () => {
    const fetchImpl = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("offline"))
      .mockResolvedValue(json(200, HEALTHY));
    renderWith(fetchImpl as unknown as typeof fetch);

    await screen.findByRole("alert");
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));

    await waitFor(() => expect(screen.getByText("Backend")).toBeInTheDocument());
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });
});
