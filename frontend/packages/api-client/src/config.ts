/**
 * Deployment values are read at boot from a runtime `config.json` served next to the app, not baked in at build
 * (ADR-0012), so one build serves any installation.
 */
export interface RuntimeConfig {
  /** Origin of the API, e.g. `https://qms.example.org`. Empty string means "same origin as the app". */
  apiOrigin: string;
}

export class RuntimeConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RuntimeConfigError";
  }
}

export async function loadRuntimeConfig(
  fetchImpl: typeof fetch = fetch,
  url = "/config.json",
): Promise<RuntimeConfig> {
  let response: Response;
  try {
    response = await fetchImpl(url, { cache: "no-store" });
  } catch (cause) {
    throw new RuntimeConfigError(`Could not load ${url}: ${String(cause)}`);
  }
  if (!response.ok) {
    throw new RuntimeConfigError(`Could not load ${url}: HTTP ${response.status}`);
  }
  const raw: unknown = await response.json().catch(() => undefined);
  if (typeof raw !== "object" || raw === null || typeof (raw as { apiOrigin?: unknown }).apiOrigin !== "string") {
    throw new RuntimeConfigError(`${url} must be a JSON object with a string "apiOrigin"`);
  }
  return { apiOrigin: (raw as { apiOrigin: string }).apiOrigin.replace(/\/+$/, "") };
}
