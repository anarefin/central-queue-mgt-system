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
import type { Channel } from "./catalogue";
import type { AgentAvailability, AvailabilityInput, BreakReport, BreakReportQuery, BreakType, BreakTypeInput } from "./breaks";
import type { PriorityClass, PriorityClassInput, PriorityDefaults, QueueDryRun, QueueStrategy, RoutingStrategy } from "./priority";
import type { NumberingPreview, NumberingRule, NumberingRuleChange, NumberingRuleInput, NumberingScope } from "./numbering";
import type { AgentDay, CompleteInput, CounterSession, OpenSessionInput, SessionCounterOption, TransferInput, TransferResult, TransferTargets } from "./sessions";
import type { TopicSnapshot } from "./stream";
import type { IssueTicketInput, QueueSnapshot, ReprioritiseInput, SiteServices, Ticket, TicketChange } from "./tickets";

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
  /** Extra request headers, such as `Idempotency-Key`. */
  headers?: Record<string, string>;
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

function ifMatch(version: number | undefined): Record<string, string> | undefined {
  return version === undefined ? undefined : { "If-Match": `"${version}"` };
}

/** A session action path, naming the ticket it acts on when the session has several in progress (FR-AGT-011). */
function sessionAction(id: string, action: string, ticketId: string | undefined): string {
  return `/sessions/${id}/${action}${ticketId === undefined ? "" : `?ticket_id=${encodeURIComponent(ticketId)}`}`;
}

function numberingPath(scope: NumberingScope, id: string): string {
  return `/${scope === "service" ? "services" : "service-groups"}/${id}`;
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
    /** The services a site offers with their live queue lengths; `channel` keeps only those that issue on that channel. */
    services: (siteId: string, channel?: Channel) =>
      this.request<SiteServices>("GET", `/sites/${siteId}/services${channel ? `?channel=${channel}` : ""}`),
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

  /**
   * Issuing needs an `Idempotency-Key` (SRS §20.1): replaying a key within 24 hours returns the original ticket, secret
   * included, and issues nothing, so retrying after a lost response is safe. `get` never returns the secret.
   */
  readonly tickets = {
    issue: (input: IssueTicketInput, idempotencyKey: string) =>
      this.request<Ticket>("POST", "/tickets", input, { headers: { "Idempotency-Key": idempotencyKey } }),
    get: (id: string) => this.request<Ticket>("GET", `/tickets/${id}`),
    /**
     * F7: close the ticket in service as `transferred` and create its successor in the target queue, with the same token number
     * and visit (FR-QUE-052, ADR-0006). The note is mandatory; `version` goes as `If-Match`.
     */
    transfer: (id: string, input: TransferInput, version?: number) =>
      this.request<TransferResult>("POST", `/tickets/${id}/transfer`, input, { headers: ifMatch(version) }),
    /**
     * Give a waiting ticket another Priority class, with a mandatory reason recorded in the audit log (FR-QUE-012). The queue is
     * ordered on every read, so the change is in the next snapshot.
     */
    setPriority: (id: string, input: ReprioritiseInput, version?: number) =>
      this.request<TicketChange>("POST", `/tickets/${id}/priority`, input, { headers: ifMatch(version) }),
    /** Cancel an active ticket (§19.1). The reason is optional; an agent may cancel only their own ticket (§5.2). */
    cancel: (id: string, reason?: string, version?: number) =>
      this.request<TicketChange>("POST", `/tickets/${id}/cancel`, reason ? { reason } : undefined, { headers: ifMatch(version) }),
  };

  readonly queues = {
    snapshot: (serviceId: string, limit?: number) =>
      this.request<QueueSnapshot>("GET", `/queues/${serviceId}${limit === undefined ? "" : `?limit=${limit}`}`),
    /** The queue in computed order with every term of every score (FR-QUE-023); `strategy` tries another one without saving it. */
    dryRun: (serviceId: string, strategy?: QueueStrategy) =>
      this.request<QueueDryRun>("GET", `/queues/${serviceId}/dry-run${strategy ? `?strategy=${strategy}` : ""}`),
  };

  /**
   * An agent's counter session (SRS §11): open one on a counter their team serves, call the next ticket, start service,
   * complete with an outcome, close; or, for a called ticket, re-announce it or miss it; or hold the ticket in service and resume it
   * later. The server holds all the state, so `current` rebuilds the console after a refresh
   * (FR-AGT-004); `current` answers `not_found` when the caller has no live session. `serve` and `complete` send the
   * ticket's `version` as `If-Match` when given, and a stale one is a `conflict` (SRS §20.1). A session with several tickets in
   * progress (parallel serving, FR-AGT-011) says which one an action is for with `ticketId`.
   */
  readonly sessions = {
    options: () => this.request<Items<SessionCounterOption>>("GET", "/sessions/options"),
    current: () => this.request<CounterSession>("GET", "/sessions/current"),
    /** The caller's own current-day counts (FR-AGT-040). */
    day: () => this.request<AgentDay>("GET", "/sessions/stats"),
    open: (input: OpenSessionInput) => this.request<CounterSession>("POST", "/sessions", input),
    close: (id: string) => this.request<CounterSession>("DELETE", `/sessions/${id}`),
    next: (id: string) => this.request<CounterSession>("POST", `/sessions/${id}/next`),
    /** F3: replay the call of the called ticket; it stays called (FR-DSP-028). */
    reannounce: (id: string, version?: number, ticketId?: string) =>
      this.request<CounterSession>("POST", sessionAction(id, "reannounce", ticketId), undefined, { headers: ifMatch(version) }),
    /** F6: the visitor is absent; the ticket returns to the queue, or closes as a no-show past the limit (FR-QUE-050). */
    miss: (id: string, version?: number, ticketId?: string) =>
      this.request<CounterSession>("POST", sessionAction(id, "miss", ticketId), undefined, { headers: ifMatch(version) }),
    /**
     * The answer to the call timeout prompt: the called ticket goes back to the queue with its original wait and its place restored,
     * and no Miss is counted. Refused with `call_not_timed_out` before the timeout has passed (FR-QUE-032).
     */
    returnToQueue: (id: string, version?: number, ticketId?: string) =>
      this.request<CounterSession>("POST", sessionAction(id, "return", ticketId), undefined, { headers: ifMatch(version) }),
    /** Call a specific waiting ticket out of order. The reason is mandatory and the call is audited (FR-AGT-012, FR-SEC-040). */
    callTicket: (id: string, ticketId: string, reason: string) =>
      this.request<CounterSession>("POST", `/sessions/${id}/call`, { ticket_id: ticketId, reason }),
    /** F8: park the ticket in service; it stays bound to the session and the counter is free to call next (FR-AGT-013). */
    hold: (id: string, version?: number, ticketId?: string) =>
      this.request<CounterSession>("POST", sessionAction(id, "hold", ticketId), undefined, { headers: ifMatch(version) }),
    /** The Services, counters and agents of the session's site the ticket in service may be transferred to (F7). */
    transferTargets: (id: string) => this.request<TransferTargets>("GET", `/sessions/${id}/transfer-targets`),
    /** Put a held ticket back in service; only the session that holds it can. */
    resume: (id: string, ticketId: string, version?: number) =>
      this.request<CounterSession>("POST", `/sessions/${id}/hold`, { ticket_id: ticketId }, { headers: ifMatch(version) }),
    /** F9: start a break of this type; the ticket in progress must be resolved first and no new ticket is assigned meanwhile (FR-AGT-021). */
    startBreak: (id: string, breakTypeId: string) => this.request<CounterSession>("POST", `/sessions/${id}/break`, { break_type_id: breakTypeId }),
    /** F9 again: end the break the session is on. */
    endBreak: (id: string) => this.request<CounterSession>("POST", `/sessions/${id}/break`),
    /** An Org or Team Admin closes a stale session; its tickets return to the front of their queues, with an audit entry (FR-AGT-002). */
    forceClose: (id: string, reason?: string) =>
      this.request<CounterSession>("POST", `/sessions/${id}/force-close`, reason ? { reason } : undefined),
    serve: (id: string, version?: number, ticketId?: string) =>
      this.request<CounterSession>("POST", sessionAction(id, "serve", ticketId), undefined, { headers: ifMatch(version) }),
    complete: (id: string, input: CompleteInput, version?: number, ticketId?: string) =>
      this.request<CounterSession>("POST", sessionAction(id, "complete", ticketId), input, { headers: ifMatch(version) }),
  };

  /**
   * Break types (FR-AGT-020), agent availability (FR-AGT-024) and the break report (FR-AGT-022). Types are replaced as a whole and
   * deactivated, never deleted; an agent may only read them. `setAvailability` acts on the agent's live session, under the same
   * rules as their own F9.
   */
  readonly breaks = {
    types: () => this.request<Items<BreakType>>("GET", "/break-types"),
    createType: (input: BreakTypeInput) => this.request<BreakType>("POST", "/break-types", input),
    updateType: (id: string, input: BreakTypeInput) => this.request<BreakType>("PUT", `/break-types/${id}`, input),
    deactivateType: (id: string, reason?: string) => this.request<BreakType>("POST", `/break-types/${id}/deactivate`, reason ? { reason } : undefined),
    activateType: (id: string) => this.request<BreakType>("POST", `/break-types/${id}/activate`),
    availability: () => this.request<Items<AgentAvailability>>("GET", "/agents/availability"),
    setAvailability: (agentId: string, input: AvailabilityInput) => this.request<AgentAvailability>("PUT", `/agents/${agentId}/availability`, input),
    report: (query: BreakReportQuery = {}) => {
      const params = new URLSearchParams();
      for (const [key, value] of Object.entries(query)) if (value) params.set(key, value);
      const text = params.toString();
      return this.request<BreakReport>("GET", `/break-report${text ? `?${text}` : ""}`);
    },
  };

  /** The polling fallback of the realtime stream (FR-QUE-084): a topic's snapshot, under the same authorisation as a subscription. */
  readonly stream = {
    snapshot: (topic: string) => this.request<TopicSnapshot>("GET", `/stream/snapshot?topic=${encodeURIComponent(topic)}`),
  };

  /**
   * Priority classes and the ordering strategy of Service groups (FR-QUE-010, FR-QUE-021). Classes are replaced as a
   * whole and deactivated, never deleted, so tickets that carry one keep resolving. Reception may only `classes`.
   */
  readonly priority = {
    classes: () => this.request<Items<PriorityClass>>("GET", "/priority-classes"),
    createClass: (input: PriorityClassInput) => this.request<PriorityClass>("POST", "/priority-classes", input),
    updateClass: (id: string, input: PriorityClassInput) => this.request<PriorityClass>("PUT", `/priority-classes/${id}`, input),
    deactivateClass: (id: string, reason?: string) =>
      this.request<PriorityClass>("POST", `/priority-classes/${id}/deactivate`, reason ? { reason } : undefined),
    activateClass: (id: string) => this.request<PriorityClass>("POST", `/priority-classes/${id}/activate`),
    strategy: (groupId: string) => this.request<RoutingStrategy>("GET", `/service-groups/${groupId}/routing-strategy`),
    setStrategy: (groupId: string, strategy: QueueStrategy) =>
      this.request<RoutingStrategy>("PUT", `/service-groups/${groupId}/routing-strategy`, { strategy }),
    /** Where a new ticket's class comes from when staff choose none: a default per channel and per Service (FR-QUE-011). */
    defaults: () => this.request<PriorityDefaults>("GET", "/priority-defaults"),
    /** Set, or with `null` clear, the class a channel gives its tickets by default. It never changes a ticket already issued (FR-CFG-041). */
    setChannelDefault: (channel: Channel, classId: string | null) =>
      this.request<PriorityDefaults["channels"][number]>("PUT", `/priority-defaults/channels/${channel}`, { priority_class_id: classId }),
    setServiceDefault: (serviceId: string, classId: string | null) =>
      this.request<PriorityDefaults["services"][number]>("PUT", `/priority-defaults/services/${serviceId}`, { priority_class_id: classId }),
  };

  /**
   * Token numbering per Service or Service group (FR-CFG-018). `setRule` replaces the rule; changing or removing one never
   * renumbers a ticket already issued (FR-CFG-041). `preview` shows the next Token number without using it up.
   */
  readonly numbering = {
    rules: (siteId: string) => this.request<Items<NumberingRule>>("GET", `/sites/${siteId}/numbering-rules`),
    setRule: (scope: NumberingScope, id: string, input: NumberingRuleInput) =>
      this.request<NumberingRuleChange>("PUT", `${numberingPath(scope, id)}/numbering-rule`, input),
    removeRule: (scope: NumberingScope, id: string) => this.request<NumberingRuleChange>("DELETE", `${numberingPath(scope, id)}/numbering-rule`),
    preview: (scope: NumberingScope, id: string) => this.request<NumberingPreview>("GET", `${numberingPath(scope, id)}/numbering-preview`),
  };

  readonly users = {
    list: (limit = 200) => this.request<UserPage>("GET", `/users?limit=${limit}`),
  };

  /**
   * `anonymous` requests (login, refresh, logout) authenticate by credentials or the refresh cookie, so they send no
   * bearer token and never trigger the refresh-and-retry, which would otherwise wait on itself.
   */
  async request<T>(method: string, path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json", ...options.headers };
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
      return this.request<T>(method, path, body, { ...options, retried: true });
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
