import { ApiRequestError, isApiErrorCode, type ApiErrorBody } from "./errors";
import type { Alert, AlertState, AlertThreshold, AlertThresholdInput } from "./alerts";
import type { Appointment, Availability, BookAppointmentInput, RescheduleAppointmentInput } from "./appointments";
import type { AppointmentSummary, SavedSite, TicketSummary, VisitorMe, VisitorTokenResponse } from "./visitor-account";
import type { RemoteJoinInput, RemoteJoinPolicy } from "./remote-join";
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
import type {
  CreatePairingCodeInput,
  DeviceBootstrap,
  DeviceCommand,
  DeviceTokenResponse,
  DeviceView,
  DisplayConfig,
  DisplayConfigInput,
  DisplayState,
  Notice,
  NoticeInput,
  PairingCodeResponse,
} from "./devices";
import type { Counter, CounterInput, Items, Site, SiteInput, Zone, ZoneInput } from "./hierarchy";
import type { Channel } from "./catalogue";
import type { AgentAvailability, AvailabilityInput, BreakReport, BreakReportQuery, BreakType, BreakTypeInput } from "./breaks";
import type { PriorityClass, PriorityClassInput, PriorityDefaults, QueueDryRun, QueueStrategy, RoutingStrategy } from "./priority";
import type { NumberingPreview, NumberingRule, NumberingRuleChange, NumberingRuleInput, NumberingScope } from "./numbering";
import type { AgentDay, CompleteInput, CounterSession, OpenSessionInput, SessionCounterOption, TransferInput, TransferResult, TransferTargets } from "./sessions";
import type { TopicSnapshot } from "./stream";
import {
  TICKET_CREDENTIAL_HEADER,
  type IssueTicketInput,
  type KioskAgentOption,
  type KioskIssueTicketInput,
  type KioskVisitorIdentity,
  type PushSubscriptionInput,
  type CheckInInput,
  type QueueSnapshot,
  type ReprioritiseInput,
  type SiteServices,
  type Ticket,
  type TicketChange,
  type VisitorTicketView,
} from "./tickets";
import type { FeedbackInput, MyFeedback, PendingFeedbackComment, SubmittedFeedback } from "./feedback";
import type {
  RegisterVisitorInput,
  VisitorImportMapping,
  VisitorImportReport,
  VisitorImportUploadInput,
  VisitorMatch,
  VisitorRegistration,
} from "./visitors";
import type { BrandingInput, OrgBranding, PrintTemplate, PrintTemplateInput } from "./branding";
import type { DashboardFilter, DashboardSnapshot } from "./dashboard";
import type {
  AuditReportPage,
  AuditReportRequest,
  DetailedTokenReportPage,
  DetailedTokenReportRequest,
  DomainReportKey,
  DomainReportPage,
  DomainReportRequest,
  OperationalReportKey,
  OperationalReportRequest,
  OperationalReportResponse,
  PeakHoursResponse,
  PlanningViewKey,
  PlanningViewRequest,
  ReportExportInput,
  ReportExportJob,
  ReportExportOutcome,
  ReportSchedule,
  ReportScheduleDelivery,
  ReportScheduleInput,
  StaffingGapResponse,
} from "./reports";
import type { IssueJourneyInput, JourneyResult, JourneySettings, JourneyTemplateSummary } from "./journeys";
import type {
  NotificationMessageQuery,
  NotificationMessageWithAttempts,
  NotificationTemplate,
  NotificationTemplateInput,
  NotificationTemplatePreview,
  NotificationTriggerCatalogueEntry,
  NotificationTriggerSetting,
  NotificationTriggerSettingInput,
} from "./notifications";

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
    /**
     * The visitor ticket page's own anonymous reads and actions (§20.2, FR-SEC-033, ticket 37): the ticket id plus its own
     * secret, never a bearer token. `visitorCancel` refuses once the ticket has been called (FR-MOB-030, `conflict` /
     * `ticket_already_called`); a wrong or missing secret is `unauthenticated`, the same as an unknown ticket id.
     */
    visitorView: (id: string, credential: string) =>
      this.request<VisitorTicketView>("GET", `/tickets/${id}/visitor`, undefined, { anonymous: true, headers: { [TICKET_CREDENTIAL_HEADER]: credential } }),
    visitorCancel: (id: string, credential: string) =>
      this.request<VisitorTicketView>("POST", `/tickets/${id}/visitor-cancel`, undefined, {
        anonymous: true,
        headers: { [TICKET_CREDENTIAL_HEADER]: credential },
      }),
    /** Opts the ticket's own visitor record out of (or back into) non-essential notifications (FR-NTF-035, ticket 38). */
    notificationOptOut: (id: string, credential: string, optedOut: boolean, consentTextVersion?: string) =>
      this.request<{ opted_out: boolean }>(
        "POST",
        `/tickets/${id}/visitor/notification-opt-out`,
        { opted_out: optedOut, consent_text_version: consentTextVersion },
        { anonymous: true, headers: { [TICKET_CREDENTIAL_HEADER]: credential } },
      ),
    /**
     * A paired kiosk issues for itself (ticket 25, §8.2): the device's own access token names the actor and the
     * channel is always `kiosk`, scoped to the device's own site server-side. Same idempotency guarantee as `issue`.
     */
    issueKiosk: (input: KioskIssueTicketInput, idempotencyKey: string) =>
      this.request<Ticket>("POST", "/kiosk/tickets", input, { headers: { "Idempotency-Key": idempotencyKey } }),
    /** Registers the visitor's own device for Web Push on this ticket (ticket 39, §18.3, FR-INT-040). */
    pushSubscribe: (id: string, credential: string, subscription: PushSubscriptionInput) =>
      this.request<{ subscribed: boolean }>("POST", `/tickets/${id}/push-subscription`, subscription, {
        anonymous: true,
        headers: { [TICKET_CREDENTIAL_HEADER]: credential },
      }),
    /**
     * A visitor marks their own remote ticket present, by site QR or geofence (ticket 43, FR-MOB-021, §19.1). Refused
     * with `conflict` / `ticket_not_remote`, `too_far` or `geofence_not_configured`.
     */
    checkIn: (id: string, credential: string, input: CheckInInput) =>
      this.request<VisitorTicketView>("POST", `/tickets/${id}/check-in`, input, {
        anonymous: true,
        headers: { [TICKET_CREDENTIAL_HEADER]: credential },
      }),
    /**
     * A visitor's own "not ready yet", once per ticket while it is still remote, if the Service allows it (ticket 43,
     * FR-MOB-031). Refused with `conflict` / `delay_not_allowed` or `delay_already_used`.
     */
    delay: (id: string, credential: string) =>
      this.request<VisitorTicketView>("POST", `/tickets/${id}/delay`, undefined, {
        anonymous: true,
        headers: { [TICKET_CREDENTIAL_HEADER]: credential },
      }),
    /**
     * A visitor's own optional feedback on their own completed ticket, once (ticket 45, FR-MOB-033). Refused with
     * `conflict` / `ticket_not_completed` or `feedback_already_submitted`.
     */
    submitFeedback: (id: string, credential: string, input: FeedbackInput) =>
      this.request<SubmittedFeedback>("POST", `/tickets/${id}/feedback`, input, {
        anonymous: true,
        headers: { [TICKET_CREDENTIAL_HEADER]: credential },
      }),
  };

  /**
   * The Web Push public key every visitor's browser needs before it can subscribe (ticket 39, RFC 8292); not
   * sensitive, anonymous. `available` (ticket 44, FR-QUE-202, FR-MOB-041) is the Site's own internet reachability:
   * false means push delivery cannot reach the visitor right now, even though the browser could still register a
   * subscription; treated as available when absent, for a fixture or an older backend that predates this field.
   */
  readonly webPush = {
    publicKey: () =>
      this.request<{ public_key: string; available?: boolean }>("GET", "/notification-config/web-push-key", undefined, { anonymous: true }),
  };

  /**
   * The kiosk's own, minimal window onto the visitor directory and a group's on-duty Agents (ticket 26, FR-ISS-012,
   * FR-ISS-013, FR-ISS-014): a paired kiosk device is the caller, so `identify` returns only name and category —
   * unlike Reception's own `visitors.lookup`, never a phone number, external code or flag.
   */
  readonly kiosk = {
    identify: (query: string) => this.request<KioskVisitorIdentity>("GET", `/kiosk/visitors/identify?q=${encodeURIComponent(query)}`),
    agentsOf: (groupId: string) => this.request<Items<KioskAgentOption>>("GET", `/kiosk/groups/${groupId}/agents`),
  };

  /**
   * The visitor directory and walk-in registration (SRS §8.3, §22.2, FR-ISS-020, FR-ISS-021). `lookup` resolves a
   * known visitor by code, phone or QR; `register` gives an unknown walk-in a minimal record and a pass reference.
   * Both are Reception actions; the API enforces the permission and which fields are captured (FR-SEC-023).
   */
  readonly visitors = {
    lookup: (query: string) => this.request<VisitorMatch>("GET", `/visitors/lookup?q=${encodeURIComponent(query)}`),
    register: (input: RegisterVisitorInput) => this.request<VisitorRegistration>("POST", "/visitors", input),
  };

  /**
   * A registered visitor's own email + OTP sign-in, silent refresh and sign-out (ticket 41, FR-MOB-001, §20.2): the
   * same shape as {@code auth}, kept entirely apart from it (its own cookie, its own refresh-token table server
   * side), so a browser signed in as both staff and a visitor never confuses the two sessions.
   */
  readonly visitorAuth = {
    requestOtp: (email: string) => this.request<void>("POST", "/auth/visitor/otp/request", { email }, { anonymous: true }),
    verifyOtp: (email: string, code: string) => this.request<VisitorTokenResponse>("POST", "/auth/visitor/otp/verify", { email, code }, { anonymous: true }),
    /** Exchanges the HttpOnly visitor refresh cookie for a new access token; the browser sends the cookie, not our code. */
    refresh: () => this.request<VisitorTokenResponse>("POST", "/auth/visitor/refresh", undefined, { anonymous: true }),
    logout: () => this.request<void>("POST", "/auth/visitor/logout", undefined, { anonymous: true }),
    me: () => this.request<VisitorMe>("GET", "/auth/visitor/me"),
  };

  /** A registered visitor's own "my account" read model (ticket 41, FR-MOB-002): active tickets, appointment
   * history, and saved sites they may add or remove. */
  readonly visitorAccount = {
    tickets: () => this.request<Items<TicketSummary>>("GET", "/visitors/me/tickets"),
    appointments: () => this.request<Items<AppointmentSummary>>("GET", "/visitors/me/appointments"),
    savedSites: () => this.request<Items<SavedSite>>("GET", "/visitors/me/saved-sites"),
    saveSite: (siteId: string) => this.request<void>("POST", `/visitors/me/saved-sites/${siteId}`),
    unsaveSite: (siteId: string) => this.request<void>("DELETE", `/visitors/me/saved-sites/${siteId}`),
  };

  /**
   * A registered visitor's own remote join of a Service's queue, before arriving (ticket 42, SRS §13.2,
   * FR-MOB-010..012). `policy` is what the join screen shows before the visitor commits (FR-MOB-023); `join` needs an
   * `Idempotency-Key`, the same guarantee `tickets.issue` already gives reception, so a retry after a lost response
   * cannot join twice. The result is a `Ticket`, the same shape any other channel's issuance already returns.
   */
  readonly remoteJoin = {
    policy: (serviceId: string) => this.request<RemoteJoinPolicy>("GET", `/remote-join/${serviceId}`),
    join: (serviceId: string, input: RemoteJoinInput, idempotencyKey: string) =>
      this.request<Ticket>("POST", `/remote-join/${serviceId}`, input, { headers: { "Idempotency-Key": idempotencyKey } }),
  };

  /**
   * Visitor master-data CSV import (FR-INT-010, FR-INT-011): the admin-set column mapping both a manual upload and
   * the scheduled folder pickup use, a manual upload's own validation report, and the run history — including a
   * scheduled run's report, which nobody was present to see synchronously.
   */
  readonly visitorImport = {
    mapping: () => this.request<VisitorImportMapping>("GET", "/visitors/import/mapping"),
    setMapping: (input: VisitorImportMapping) => this.request<VisitorImportMapping>("PUT", "/visitors/import/mapping", input),
    upload: (input: VisitorImportUploadInput) => this.request<VisitorImportReport>("POST", "/visitors/import", input),
    runs: () => this.request<Items<VisitorImportReport>>("GET", "/visitors/import/runs"),
    run: (id: string) => this.request<VisitorImportReport>("GET", `/visitors/import/runs/${id}`),
  };

  /** Organisation branding and the printed-token template (ticket 27, FR-CFG-030..032). */
  readonly branding = {
    get: () => this.request<OrgBranding>("GET", "/branding"),
    update: (input: BrandingInput) => this.request<OrgBranding>("PUT", "/branding", input),
    template: () => this.request<PrintTemplate>("GET", "/print-template"),
    updateTemplate: (input: PrintTemplateInput) => this.request<PrintTemplate>("PUT", "/print-template", input),
  };

  /**
   * Journeys and multi-stop Visits (ticket 31, FR-ISS-022): issuing a Journey needs an `Idempotency-Key`, the same
   * guarantee as issuing a single ticket. `templatesForSite` lists what Reception's picker offers; `settings` is the
   * feature flag an Org Admin turns on "per profile" before Reception can issue any Journey at all.
   */
  readonly journeys = {
    issue: (input: IssueJourneyInput, idempotencyKey: string) =>
      this.request<JourneyResult>("POST", "/journeys", input, { headers: { "Idempotency-Key": idempotencyKey } }),
    templatesForSite: (siteId: string) => this.request<JourneyTemplateSummary[]>("GET", `/sites/${siteId}/journey-templates`),
    settings: () => this.request<JourneySettings>("GET", "/journey-settings"),
    updateSettings: (input: JourneySettings) => this.request<JourneySettings>("PUT", "/journey-settings", input),
  };

  readonly queues = {
    snapshot: (serviceId: string, limit?: number) =>
      this.request<QueueSnapshot>("GET", `/queues/${serviceId}${limit === undefined ? "" : `?limit=${limit}`}`),
    /** The queue in computed order with every term of every score (FR-QUE-023); `strategy` tries another one without saving it. */
    dryRun: (serviceId: string, strategy?: QueueStrategy) =>
      this.request<QueueDryRun>("GET", `/queues/${serviceId}/dry-run${strategy ? `?strategy=${strategy}` : ""}`),
  };

  /**
   * The supervisor's live dashboard (SRS §15.1, ticket 46): {@code live} answers every FR-MON-003 tile under a filter
   * that is also this screen's own shareable URL (FR-MON-002) — the caller decides what the query string holds,
   * this just forwards it. `site:{id}:dashboard` (the realtime channel FR-MON-001 refreshes through) carries no tile
   * data of its own, only a refresh signal, so a live view re-calls `live` on every one rather than trust a broadcast
   * payload that could not be scoped to the caller's own reach (FR-CFG-105).
   */
  readonly dashboard = {
    live: (filter: DashboardFilter) => {
      const params = new URLSearchParams();
      for (const [key, value] of Object.entries(filter)) if (value) params.set(key, value);
      return this.request<DashboardSnapshot>("GET", `/dashboard/live?${params.toString()}`);
    },
    /** A supervisor's own free-text message to the Site's team and org admins (FR-MON-004), on the same
     * `staff-alert:{site_id}` channel ticket 38 already gives the automatic threshold alerts of ticket 47. */
    sendStaffAlert: (siteId: string, message: string) => this.request<void>("POST", `/dashboard/${siteId}/staff-alert`, { message }),
  };

  /**
   * Reports (SRS §16, ticket 48): {@code run} answers one report's page, filtered (FR-RPT-001), paged and sorted
   * on screen (FR-RPT-002). Only {@code detailed-token} exists yet; later tickets (49-51) grow the catalogue.
   */
  readonly reports = {
    run: (key: string, request: DetailedTokenReportRequest = {}) => this.request<DetailedTokenReportPage>("POST", `/reports/${key}/run`, request),
    /** The six operational report keys (ticket 50, §16.1, §15.2, §15.3): grouped rows for the requested period,
     * plus a period-over-period comparison (FR-RPT-010, FR-MON-011). */
    runOperational: (key: OperationalReportKey, request: OperationalReportRequest) =>
      this.request<OperationalReportResponse>("POST", `/reports/${key}/run`, request),
    /** The four row-shaped report keys ticket 51 adds (§16.1: appointment, journey, feedback, notification). */
    runDomain: (key: DomainReportKey, request: DomainReportRequest = {}) =>
      this.request<DomainReportPage>("POST", `/reports/${key}/run`, request),
    /** The Audit report key (ticket 51, §16.1): reuses the existing `/audit` read under this catalogue's own
     * path, cursor-paged rather than `page`/`size`. Additionally gated on `audit:read` server-side. */
    runAudit: (request: AuditReportRequest = {}) => this.request<AuditReportPage>("POST", "/reports/audit/run", request),
    /** The two staffing-planning views ticket 51 adds (§16.2: peak-hours FR-RPT-011, staffing-gap FR-RPT-012). */
    runPlanningView: (key: PlanningViewKey, request: PlanningViewRequest) =>
      this.request<PeakHoursResponse | StaffingGapResponse>("POST", `/reports/${key}/run`, request),
    /** FR-RPT-003/004: the file itself (200) when the filtered row count is under the configured threshold, or a
     * job id (202) to poll with `job` otherwise. */
    export: (key: string, input: ReportExportInput) => this.requestExport(`/reports/${key}/export`, input),
    job: (id: string) => this.request<ReportExportJob>("GET", `/reports/jobs/${id}`),
    /** The finished file behind a `done` job's own expiring link (FR-RPT-004); `not_found` once expired. */
    download: (id: string) => this.requestBlob(`/reports/jobs/${id}/download`),
    /** Scheduled report delivery (ticket 52, FR-RPT-005): any report key from the catalogue above, emailed to a
     * named list daily, weekly or monthly, in a chosen format. Creating or changing a schedule needs `reports:
     * run_export` and `visitor_pii:view`, the same gate an export itself already carries; viewing one or its
     * delivery log only needs `reports:run_export`. */
    schedules: {
      list: () => this.request<Items<ReportSchedule>>("GET", "/reports/schedules"),
      get: (id: string) => this.request<ReportSchedule>("GET", `/reports/schedules/${id}`),
      create: (input: ReportScheduleInput) => this.request<ReportSchedule>("POST", "/reports/schedules", input),
      update: (id: string, input: ReportScheduleInput) => this.request<ReportSchedule>("PUT", `/reports/schedules/${id}`, input),
      remove: (id: string) => this.request<void>("DELETE", `/reports/schedules/${id}`),
      deliveries: (id: string) => this.request<Items<ReportScheduleDelivery>>("GET", `/reports/schedules/${id}/deliveries`),
    },
  };

  /**
   * Threshold alerts (SRS §15.4, §11.3 FR-AGT-023, ticket 47): a Service's own thresholds (FR-MON-020), a Site's
   * current alerts on `site:{id}:alerts` (`alert.raised` / `alert.acknowledged`), and acknowledging one with an
   * optional note (FR-MON-022). Raising an alert is system-driven; there is no write here for it.
   */
  readonly alerts = {
    thresholds: (serviceId: string) => this.request<AlertThreshold>("GET", `/services/${serviceId}/alert-thresholds`),
    setThresholds: (serviceId: string, input: AlertThresholdInput) =>
      this.request<AlertThreshold>("PUT", `/services/${serviceId}/alert-thresholds`, input),
    list: (siteId: string, state?: AlertState) => this.request<Items<Alert>>("GET", `/sites/${siteId}/alerts${state ? `?state=${state}` : ""}`),
    acknowledge: (id: string, note?: string) => this.request<Alert>("POST", `/alerts/${id}/acknowledge`, note ? { note } : undefined),
  };

  /**
   * Appointment availability search and staff booking (ticket 32, 33; SRS §9.2, FR-APT-010..016). `book` needs no
   * `Idempotency-Key`: two identical requests would each check the slot's remaining capacity for themselves and, past
   * the last seat, the second is refused `conflict` (`slot_full`), never doubled.
   */
  readonly appointments = {
    availability: (serviceId: string, date: string) => this.request<Availability>("GET", `/services/${serviceId}/appointments/availability?date=${date}`),
    book: (input: BookAppointmentInput) => this.request<Appointment>("POST", "/appointments", input),
    /** FR-APT-020, FR-APT-021: moves the appointment to a new slot, keeping its reference code. */
    reschedule: (id: string, input: RescheduleAppointmentInput) => this.request<Appointment>("PATCH", `/appointments/${id}`, input),
    /** FR-APT-020, FR-APT-022: cancels the appointment, freeing its slot's capacity at once. */
    cancel: (id: string, reason?: string) => this.request<void>("DELETE", `/appointments/${id}`, reason ? { reason } : undefined),
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
   * The notification pipeline's admin surface (ticket 38, SRS §14): the read-only trigger catalogue, each trigger's
   * enabled state and channel order per Site (and, given a `serviceId`, per Service under it, overriding the Site's
   * own setting, FR-NTF-010), templates per trigger x channel x language with a preview (FR-NTF-020, FR-NTF-021),
   * and the delivery log filterable by ticket, visitor and status (FR-NTF-032).
   */
  readonly notifications = {
    catalogue: () => this.request<Items<NotificationTriggerCatalogueEntry>>("GET", "/notification-triggers/catalogue"),
    triggers: (siteId: string, serviceId?: string) =>
      this.request<Items<NotificationTriggerSetting>>(
        "GET", `/notification-triggers?site_id=${siteId}${serviceId ? `&service_id=${serviceId}` : ""}`),
    setTrigger: (triggerKey: string, siteId: string, input: NotificationTriggerSettingInput, serviceId?: string) =>
      this.request<NotificationTriggerSetting>(
        "PUT", `/notification-triggers/${triggerKey}?site_id=${siteId}${serviceId ? `&service_id=${serviceId}` : ""}`, input),
    templatesForTrigger: (triggerKey: string) => this.request<Items<NotificationTemplate>>("GET", `/notification-templates/${triggerKey}`),
    saveTemplate: (triggerKey: string, channel: string, language: string, input: NotificationTemplateInput) =>
      this.request<NotificationTemplate>("PUT", `/notification-templates/${triggerKey}/${channel}/${language}`, input),
    previewTemplate: (triggerKey: string, channel: string, language: string) =>
      this.request<NotificationTemplatePreview>("GET", `/notification-templates/${triggerKey}/${channel}/${language}/preview`),
    messages: (query: NotificationMessageQuery = {}) => {
      const params = new URLSearchParams();
      if (query.ticketId) params.set("ticket_id", query.ticketId);
      if (query.visitorId) params.set("visitor_id", query.visitorId);
      if (query.status) params.set("status", query.status);
      if (query.limit) params.set("limit", String(query.limit));
      const qs = params.toString();
      return this.request<Items<NotificationMessageWithAttempts>>("GET", `/notification-messages${qs ? `?${qs}` : ""}`);
    },
  };

  /**
   * Device pairing, silent refresh, heartbeat and bootstrap (device-authenticated, ticket 24), plus fleet
   * administration (`config:org_sites_zones`, since a device sits under a Site/Zone like a Counter): issuing a
   * pairing code, the central health view, revoking a device, and pushing it a reload or configuration update over
   * its `device:{id}` realtime topic (FR-OPS-011, FR-OPS-041, FR-OPS-042).
   */
  readonly devices = {
    pair: (code: string) => this.request<DeviceTokenResponse>("POST", "/devices/pair", { code }, { anonymous: true }),
    /** Rotates the device's own refresh credential; unlike staff refresh, the token travels in the body (API-017). */
    refresh: (refreshToken: string) =>
      this.request<DeviceTokenResponse>("POST", "/devices/refresh", { refresh_token: refreshToken }, { anonymous: true }),
    heartbeat: (id: string, appVersion: string) => this.request<void>("POST", `/devices/${id}/heartbeat`, { app_version: appVersion }),
    bootstrap: () => this.request<DeviceBootstrap>("GET", "/config/bootstrap"),
    createPairingCode: (input: CreatePairingCodeInput) => this.request<PairingCodeResponse>("POST", "/devices/pairing-codes", input),
    list: () => this.request<Items<DeviceView>>("GET", "/devices"),
    get: (id: string) => this.request<DeviceView>("GET", `/devices/${id}`),
    revoke: (id: string) => this.request<DeviceView>("POST", `/devices/${id}/revoke`),
    command: (id: string, command: DeviceCommand) => this.request<void>("POST", `/devices/${id}/commands`, { command }),
    /** The display board's own resume-after-power-loss read (device-authenticated, ticket 28, FR-DSP-012). */
    displayState: (id: string) => this.request<DisplayState>("GET", `/devices/${id}/display-state`),
    /** Sets a display's layout (and its zone-proportion config), language cycle (and its interval), columns, next-N
     * depth, highlight period and zone assignment (staff, ticket 28/30). */
    updateDisplayConfig: (id: string, input: DisplayConfigInput) =>
      this.request<DisplayConfig>("PUT", `/devices/${id}/display-config`, input),
  };

  /**
   * Notice-board content (ticket 30, FR-DSP-006, `notice_board:manage`): images, video or rich text, scheduled per
   * item, scoped to a Zone. Notices are deactivated, never deleted, so a scheduled item already shown keeps its history.
   */
  readonly notices = {
    forZone: (zoneId: string) => this.request<Items<Notice>>("GET", `/zones/${zoneId}/notices`),
    create: (input: NoticeInput) => this.request<Notice>("POST", "/notices", input),
    update: (id: string, input: NoticeInput) => this.request<Notice>("PUT", `/notices/${id}`, input),
    deactivate: (id: string) => this.request<Notice>("POST", `/notices/${id}/deactivate`),
    activate: (id: string) => this.request<Notice>("POST", `/notices/${id}/activate`),
  };

  /**
   * The staff side of post-service feedback (ticket 45, FR-MOB-033): a Team Admin's own review queue and decision on
   * an individual comment, and an Agent's own read of their feedback, comment included only once approved. Neither
   * has a row in the SRS §5.2 permission matrix, so both are role-checked server-side rather than by a permission.
   */
  readonly feedback = {
    pendingComments: () => this.request<Items<PendingFeedbackComment>>("GET", "/feedback/pending-comments"),
    approveComment: (id: string) => this.request<{ id: string; ticket_id: string; comment_approved: boolean }>("POST", `/feedback/${id}/approve-comment`),
    mine: () => this.request<Items<MyFeedback>>("GET", "/feedback/mine"),
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

  /** {@code POST /reports/{key}/export}: unlike every other call, its 200 body is the file itself, not JSON, so it
   * cannot go through {@link request}. A 202 is the async job id instead (FR-RPT-004). */
  private async requestExport(path: string, body: unknown): Promise<ReportExportOutcome> {
    const response = await this.rawFetch("POST", path, body);
    if (response.status === 202) {
      const job = (await response.json()) as { id: string };
      return { kind: "queued", jobId: job.id };
    }
    if (!response.ok) throw await toError(response);
    return { kind: "ready", blob: await response.blob(), filename: filenameFromDisposition(response.headers.get("Content-Disposition")) };
  }

  /** {@code GET /reports/jobs/{id}/download}: the finished export file. */
  private async requestBlob(path: string): Promise<{ kind: "ready"; blob: Blob; filename: string }> {
    const response = await this.rawFetch("GET", path);
    if (!response.ok) throw await toError(response);
    return { kind: "ready", blob: await response.blob(), filename: filenameFromDisposition(response.headers.get("Content-Disposition")) };
  }

  private async rawFetch(method: string, path: string, body?: unknown): Promise<Response> {
    const headers: Record<string, string> = { Accept: "application/json, */*" };
    const token = this.getAccessToken();
    if (token) headers.Authorization = `Bearer ${token}`;
    const language = this.getLanguage();
    if (language) headers["Accept-Language"] = language;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    try {
      return await this.fetchImpl(`${this.origin}${API_BASE_PATH}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: "same-origin",
      });
    } catch (cause) {
      throw new ApiRequestError(0, "network_error", String(cause));
    }
  }
}

function filenameFromDisposition(header: string | null): string {
  const match = header?.match(/filename="([^"]+)"/);
  return match?.[1] ?? "export";
}

async function toError(response: Response): Promise<ApiRequestError> {
  const parsed: unknown = await response.json().catch(() => undefined);
  const body = (parsed as { error?: Partial<ApiErrorBody> } | undefined)?.error;
  if (body && isApiErrorCode(body.code) && typeof body.message === "string") {
    return new ApiRequestError(response.status, body.code, body.message, body as ApiErrorBody);
  }
  return new ApiRequestError(response.status, "unexpected_response", `HTTP ${response.status}`);
}
