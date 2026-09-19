import { ApiRequestError, isApiErrorCode, type ApiErrorBody } from "./errors";
import type {
  CounterLink,
  CounterOption,
  OutcomeCode,
  OutcomeCodeInput,
  ServiceEntry,
  ServiceGroup,
  ServiceGroupInput,
  ServiceInput,
  Team,
  UserPage,
} from "./catalogue";
import type { Counter, CounterInput, Items, Site, SiteInput, Zone, ZoneInput } from "./hierarchy";

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

  /** Deactivation is soft: an inactive record still resolves by id (FR-CFG-001). Deactivating a parent takes its children. */
  readonly sites = {
    list: () => this.request<Items<Site>>("GET", "/sites"),
    get: (id: string) => this.request<Site>("GET", `/sites/${id}`),
    create: (input: SiteInput) => this.request<Site>("POST", "/sites", input),
    update: (id: string, input: Partial<SiteInput>) => this.request<Site>("PATCH", `/sites/${id}`, input),
    deactivate: (id: string, reason?: string) => this.request<Site>("POST", `/sites/${id}/deactivate`, reason ? { reason } : undefined),
    activate: (id: string) => this.request<Site>("POST", `/sites/${id}/activate`),
    zones: (siteId: string) => this.request<Items<Zone>>("GET", `/sites/${siteId}/zones`),
    createZone: (siteId: string, input: ZoneInput) => this.request<Zone>("POST", `/sites/${siteId}/zones`, input),
  };

  readonly zones = {
    get: (id: string) => this.request<Zone>("GET", `/zones/${id}`),
    update: (id: string, input: Partial<ZoneInput>) => this.request<Zone>("PATCH", `/zones/${id}`, input),
    deactivate: (id: string, reason?: string) => this.request<Zone>("POST", `/zones/${id}/deactivate`, reason ? { reason } : undefined),
    activate: (id: string) => this.request<Zone>("POST", `/zones/${id}/activate`),
    counters: (zoneId: string) => this.request<Items<Counter>>("GET", `/zones/${zoneId}/counters`),
    createCounter: (zoneId: string, input: CounterInput) => this.request<Counter>("POST", `/zones/${zoneId}/counters`, input),
  };

  readonly counters = {
    get: (id: string) => this.request<Counter>("GET", `/counters/${id}`),
    update: (id: string, input: Partial<CounterInput>) => this.request<Counter>("PATCH", `/counters/${id}`, input),
    deactivate: (id: string, reason?: string) => this.request<Counter>("POST", `/counters/${id}/deactivate`, reason ? { reason } : undefined),
    activate: (id: string) => this.request<Counter>("POST", `/counters/${id}/activate`),
  };

  /**
   * Service groups, services, counter links, teams and outcome codes (FR-CFG-010..015). A service is deleted only while
   * no ticket refers to it; otherwise the API answers `conflict` and deactivating is the way out.
   */
  readonly catalogue = {
    groups: (siteId: string) => this.request<Items<ServiceGroup>>("GET", `/sites/${siteId}/service-groups`),
    createGroup: (siteId: string, input: ServiceGroupInput) => this.request<ServiceGroup>("POST", `/sites/${siteId}/service-groups`, input),
    updateGroup: (id: string, input: Partial<ServiceGroupInput>) => this.request<ServiceGroup>("PATCH", `/service-groups/${id}`, input),
    deactivateGroup: (id: string, reason?: string) =>
      this.request<ServiceGroup>("POST", `/service-groups/${id}/deactivate`, reason ? { reason } : undefined),
    activateGroup: (id: string) => this.request<ServiceGroup>("POST", `/service-groups/${id}/activate`),
    services: (groupId: string) => this.request<Items<ServiceEntry>>("GET", `/service-groups/${groupId}/services`),
    createService: (groupId: string, input: ServiceInput) => this.request<ServiceEntry>("POST", `/service-groups/${groupId}/services`, input),
    updateService: (id: string, input: Partial<ServiceInput>) => this.request<ServiceEntry>("PATCH", `/services/${id}`, input),
    deactivateService: (id: string, reason?: string) =>
      this.request<ServiceEntry>("POST", `/services/${id}/deactivate`, reason ? { reason } : undefined),
    activateService: (id: string) => this.request<ServiceEntry>("POST", `/services/${id}/activate`),
    deleteService: (id: string) => this.request<void>("DELETE", `/services/${id}`),
    counterOptions: (groupId: string) => this.request<Items<CounterOption>>("GET", `/service-groups/${groupId}/counters`),
    links: (serviceId: string) => this.request<Items<CounterLink>>("GET", `/services/${serviceId}/counters`),
    /** Links the counter, or changes an existing link's weight; the weight defaults to 1, the primary counter. */
    link: (serviceId: string, counterId: string, preferenceWeight?: number) =>
      this.request<CounterLink>("PUT", `/services/${serviceId}/counters/${counterId}`, preferenceWeight === undefined ? undefined : { preference_weight: preferenceWeight }),
    unlink: (serviceId: string, counterId: string) => this.request<void>("DELETE", `/services/${serviceId}/counters/${counterId}`),
    outcomes: (serviceId: string) => this.request<Items<OutcomeCode>>("GET", `/services/${serviceId}/outcome-codes`),
    createOutcome: (serviceId: string, input: OutcomeCodeInput) => this.request<OutcomeCode>("POST", `/services/${serviceId}/outcome-codes`, input),
    updateOutcome: (id: string, input: Partial<Omit<OutcomeCodeInput, "code">>) => this.request<OutcomeCode>("PATCH", `/outcome-codes/${id}`, input),
    deactivateOutcome: (id: string, reason?: string) =>
      this.request<OutcomeCode>("POST", `/outcome-codes/${id}/deactivate`, reason ? { reason } : undefined),
    activateOutcome: (id: string) => this.request<OutcomeCode>("POST", `/outcome-codes/${id}/activate`),
    team: (groupId: string) => this.request<Team>("GET", `/service-groups/${groupId}/team`),
    /** A direct change by an Org Admin; a Team Admin asks through an approval request instead (FR-CFG-102). */
    addMember: (groupId: string, userId: string) => this.request<Team>("POST", `/service-groups/${groupId}/team/members`, { user_id: userId }),
    removeMember: (groupId: string, userId: string) => this.request<Team>("DELETE", `/service-groups/${groupId}/team/members/${userId}`),
  };

  readonly users = {
    list: (limit = 200) => this.request<UserPage>("GET", `/users?limit=${limit}`),
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
