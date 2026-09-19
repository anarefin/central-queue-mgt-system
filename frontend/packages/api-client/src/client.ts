import { ApiRequestError, isApiErrorCode, type ApiErrorBody } from "./errors";

export const API_BASE_PATH = "/api/v1";

export interface ApiClientOptions {
  apiOrigin: string;
  fetch?: typeof fetch;
  /** Supplies the in-memory access token; it is never read from or written to web storage (API-017). */
  getAccessToken?: () => string | null;
  /** Language to send as `Accept-Language`, so API messages come back localised. */
  getLanguage?: () => string | undefined;
  /**
   * Called when the API rejects a token that was sent with `token_invalid` (typically an expired access token).
   * Returning true means a fresh token is now available and the request is retried exactly once.
   */
  onTokenInvalid?: () => Promise<boolean>;
}

export interface RequestOptions {
  anonymous?: boolean;
  retried?: boolean;
}

export interface TokenResponse {
  access_token: string;
  token_type: "Bearer";
  /** Seconds until the access token expires (at most 900). */
  expires_in: number;
  /** Present and true only when the password has passed its configured age. */
  password_expired?: boolean;
}

export interface Me {
  id: string;
  username: string;
  display_name: string | null;
  preferred_language: string | null;
  roles: string[];
  sites: string[];
  groups: string[];
}

export type DependencyState = "up" | "down" | "not_configured";

export interface DependencyHealth {
  status: "up" | "down";
  dependencies: Record<string, { status: DependencyState }>;
}

export class ApiClient {
  private readonly origin: string;
  private readonly fetchImpl: typeof fetch;
  private readonly getAccessToken: () => string | null;
  private readonly getLanguage: () => string | undefined;
  private readonly onTokenInvalid?: () => Promise<boolean>;

  constructor(options: ApiClientOptions) {
    this.origin = options.apiOrigin.replace(/\/+$/, "");
    this.fetchImpl = options.fetch ?? ((...args) => fetch(...args));
    this.getAccessToken = options.getAccessToken ?? (() => null);
    this.getLanguage = options.getLanguage ?? (() => undefined);
    this.onTokenInvalid = options.onTokenInvalid;
  }

  readonly health = {
    live: () => this.request<{ status: "up" }>("GET", "/health/live"),
    ready: () => this.request<{ status: "up" }>("GET", "/health/ready"),
    dependencies: () => this.request<DependencyHealth>("GET", "/health/dependencies"),
  };

  readonly auth = {
    login: (username: string, password: string) =>
      this.request<TokenResponse>("POST", "/auth/login", { username, password }, { anonymous: true }),
    /** Exchanges the HttpOnly refresh cookie for a new access token; the browser sends the cookie, not our code. */
    refresh: () => this.request<TokenResponse>("POST", "/auth/refresh", undefined, { anonymous: true }),
    logout: () => this.request<void>("POST", "/auth/logout", undefined, { anonymous: true }),
    me: () => this.request<Me>("GET", "/auth/me"),
  };

  /**
   * `anonymous` requests (login, refresh, logout) authenticate by credentials or the refresh cookie, so they send no
   * bearer token and never trigger the refresh-and-retry, which would otherwise wait on itself.
   */
  async request<T>(method: string, path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    const token = options.anonymous ? null : this.getAccessToken();
    if (token) headers.Authorization = `Bearer ${token}`;
    const language = this.getLanguage();
    if (language) headers["Accept-Language"] = language;
    if (body !== undefined) headers["Content-Type"] = "application/json";

    let response: Response;
    try {
      response = await this.fetchImpl(`${this.origin}${API_BASE_PATH}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: "same-origin",
      });
    } catch (cause) {
      throw new ApiRequestError(0, "network_error", String(cause));
    }

    if (response.ok) {
      if (response.status === 204) return undefined as T;
      return (await response.json()) as T;
    }
    const error = await toError(response);
    // An expired access token: refresh once and retry, but never loop and never for a request that sent no token.
    if (error.code === "token_invalid" && token && !options.retried && this.onTokenInvalid && (await this.onTokenInvalid())) {
      return this.request<T>(method, path, body, { retried: true });
    }
    throw error;
  }
}

async function toError(response: Response): Promise<ApiRequestError> {
  const parsed: unknown = await response.json().catch(() => undefined);
  const body = (parsed as { error?: Partial<ApiErrorBody> } | undefined)?.error;
  if (body && isApiErrorCode(body.code) && typeof body.message === "string") {
    return new ApiRequestError(response.status, body.code, body.message, body as ApiErrorBody);
  }
  return new ApiRequestError(response.status, "unexpected_response", `HTTP ${response.status}`);
}
