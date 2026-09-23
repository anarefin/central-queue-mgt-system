"use client";

import {
  ApiRequestError,
  newIdempotencyKey,
  type ApiClient,
  type DeviceBootstrap,
  type KioskAgentOption,
  type PrintField,
  type Ticket,
} from "@qms/api-client";
import { I18nProvider, useI18n } from "@qms/i18n/react";
import { Button, cn, deriveBrandColors, ErrorAlert, QrCode, TextField } from "@qms/ui";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type CSSProperties,
  type FormEvent,
  type ReactNode,
} from "react";
import { DEFAULT_INACTIVITY_TIMEOUT_MS, useInactivityTimeout } from "../lib/inactivity";
import { localisedName } from "../lib/localised-name";
import { BrowserTokenPrinter, type PrintPayload, type TokenPrinter } from "../lib/token-printer";

type VisitorIdentifier = "not_required" | "optional" | "mandatory";

interface ServiceVM {
  id: string;
  name: string;
  visitorIdentifier: VisitorIdentifier;
}

interface CustomLevelOptionVM {
  id: string;
  name: string;
}

interface GroupVM {
  id: string;
  name: string;
  services: ServiceVM[];
  /**
   * The individual-agent level (FR-ISS-012) is offered only when both are true: a group has exactly one team
   * (CONTEXT.md), so the team level is never its own screen, and `teamSelectable` instead gates whether picking an
   * individual is in play at all (see the backend `ServiceGroup`'s header, ticket 26).
   */
  teamSelectable: boolean;
  individualSelectable: boolean;
  /** The group's fifth, custom level (FR-ISS-010); null when the group has none configured. */
  customLevel: { name: string; options: CustomLevelOptionVM[] } | null;
}

/** What the visitor has picked so far, carried step to step; every field but the Service is optional. */
interface Selection {
  groupId: string;
  groupName: string;
  serviceId: string;
  serviceName: string;
  visitorId?: string;
  visitorName?: string;
  visitorCategory?: string | null;
  agentId?: string;
  agentName?: string;
  customLevelId?: string;
  customLevelName?: string;
}

type IdentifyMethod = "code" | "phone";

type Step =
  | { kind: "idle" }
  | { kind: "empty" }
  | { kind: "group" }
  | { kind: "service"; groupId: string; groupName: string }
  | { kind: "identify"; selection: Selection; mandatory: boolean }
  | { kind: "identifyEnter"; selection: Selection; mandatory: boolean; method: IdentifyMethod }
  | { kind: "identifyScan"; selection: Selection; mandatory: boolean }
  | { kind: "individual"; selection: Selection; agents: KioskAgentOption[] }
  | { kind: "custom"; selection: Selection; options: CustomLevelOptionVM[] }
  | { kind: "confirm"; selection: Selection }
  | { kind: "issuing"; selection: Selection; idempotencyKey: string }
  | { kind: "result"; ticket: Ticket; selection: Selection; printFailed: boolean }
  | { kind: "error"; selection: Selection; idempotencyKey: string; message: string };

const ACCESSIBILITY_STORAGE_KEY = "qms-kiosk-accessibility";

interface AccessibilityPrefs {
  highContrast: boolean;
  largeText: boolean;
}

function loadAccessibilityPrefs(): AccessibilityPrefs {
  if (typeof window === "undefined") return { highContrast: false, largeText: false };
  try {
    const raw = window.localStorage.getItem(ACCESSIBILITY_STORAGE_KEY);
    if (!raw) return { highContrast: false, largeText: false };
    const parsed = JSON.parse(raw) as Partial<AccessibilityPrefs>;
    return { highContrast: Boolean(parsed.highContrast), largeText: Boolean(parsed.largeText) };
  } catch {
    return { highContrast: false, largeText: false };
  }
}

/** A device setting (FR-ISS-017), not a visitor secret, so unlike the refresh credential (API-017) this may live in localStorage. */
function saveAccessibilityPrefs(prefs: AccessibilityPrefs): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(ACCESSIBILITY_STORAGE_KEY, JSON.stringify(prefs));
  } catch {
    // A kiosk with storage disabled just keeps the default for this run; nothing to recover from.
  }
}

function groupsFromBootstrap(bootstrap: DeviceBootstrap, language: string, defaultLanguage: string): GroupVM[] {
  return bootstrap.service_tree
    .filter((group) => group.services.length > 0)
    .map((group) => ({
      id: group.id,
      name: localisedName(group.name_i18n, language, defaultLanguage),
      services: group.services.map((service) => ({
        id: service.id,
        name: localisedName(service.name_i18n, language, defaultLanguage),
        visitorIdentifier: service.visitor_identifier,
      })),
      teamSelectable: group.team_selectable,
      individualSelectable: group.individual_selectable,
      customLevel: group.custom_level
        ? {
            name: localisedName(group.custom_level.name_i18n, language, defaultLanguage),
            options: group.custom_level.options.map((option) => ({
              id: option.id,
              name: localisedName(option.name_i18n, language, defaultLanguage),
            })),
          }
        : null,
    }));
}

/** The single-option custom level is picked automatically, same rule as every other level (§8.2). */
function customOrConfirmStep(selection: Selection, group: GroupVM): Step {
  const level = group.customLevel;
  if (!level || level.options.length === 0) return { kind: "confirm", selection };
  if (level.options.length === 1) {
    const only = level.options[0]!;
    return { kind: "confirm", selection: { ...selection, customLevelId: only.id, customLevelName: only.name } };
  }
  return { kind: "custom", selection, options: level.options };
}

/**
 * The URL the printer-failure QR opens (FR-ISS-016): the visitor's mobile ticket-status page (ticket 37), same origin
 * as this kiosk (ADR-0012). The Ticket id is not sensitive on its own (FR-SEC-033) and rides the query string; the
 * secret rides the URL fragment instead (`#s=`), which a browser never sends to any server and always strips from the
 * `Referer` it gives another origin (such as an admin-configured wayfinding or branding image URL, ticket 27/37) —
 * a query string does neither, and would otherwise put the secret in a proxy's own access log (API-018).
 */
function ticketStatusUrl(ticket: Ticket): string {
  const origin = typeof window === "undefined" ? "" : window.location.origin;
  const params = new URLSearchParams({ t: ticket.id });
  return `${origin}/visitor/?${params.toString()}#s=${encodeURIComponent(ticket.secret ?? "")}`;
}

function qrScanningSupported(): boolean {
  return typeof window !== "undefined" && "BarcodeDetector" in window && typeof navigator !== "undefined" && Boolean(navigator.mediaDevices);
}

/** The FR-SEC-020 "printed token" row's default visible set, used only when a bootstrap predates ticket 27's template. */
const DEFAULT_PRINT_FIELDS: PrintField[] = ["token_number", "floor", "service_group", "visitor_code", "visitor_name", "visitor_category", "issue_time"];

/** Everything the printed token needs (ticket 27, FR-CFG-030, FR-CFG-031): the issued Ticket, the visitor's own
 * selections (the API's Ticket never carries visitor name/category/code), and the admin's saved branding and
 * template, read from the same bootstrap the kiosk already has (no second round trip). */
function printPayloadFor(ticket: Ticket, selection: Selection, bootstrap: DeviceBootstrap): PrintPayload {
  const wait = ticket.estimated_wait_minutes;
  return {
    tokenNumber: ticket.token_number,
    serviceName: selection.serviceName,
    groupName: selection.groupName,
    building: ticket.zone?.building_label ?? null,
    floor: ticket.zone?.floor_label ?? null,
    visitorCode: selection.visitorId ?? null,
    visitorName: selection.visitorName ?? null,
    visitorCategory: selection.visitorCategory ?? null,
    // A kiosk-issued ticket is never pre-assigned a counter; the field still prints when the admin enables it, blank.
    counter: null,
    issueTime: ticket.issued_at,
    estimatedWait: wait ? `${wait.low}–${wait.high}` : null,
    qrValue: ticketStatusUrl(ticket),
    noticeLine: bootstrap.print_template?.notice_line ?? null,
    fields: bootstrap.print_template?.fields ?? DEFAULT_PRINT_FIELDS,
    orgName: bootstrap.branding.org_name ?? bootstrap.branding.site_name,
    primaryColor: bootstrap.branding.primary_color ?? "",
    logoUrl: bootstrap.branding.logo_url ?? null,
  };
}

function printFieldLabel(t: (key: string) => string, field: PrintField): string {
  return t(`kiosk.printSlip.fields.${field}`);
}

function printFieldValue(payload: PrintPayload, field: PrintField): string | null {
  switch (field) {
    case "token_number":
      return payload.tokenNumber;
    case "building":
      return payload.building;
    case "floor":
      return payload.floor;
    case "service_group":
      return payload.groupName;
    case "service":
      return payload.serviceName;
    case "visitor_code":
      return payload.visitorCode;
    case "visitor_name":
      return payload.visitorName;
    case "visitor_category":
      return payload.visitorCategory;
    case "counter":
      return payload.counter;
    case "issue_time":
      return new Date(payload.issueTime).toLocaleString();
    case "estimated_wait":
      return payload.estimatedWait ? `${payload.estimatedWait} min` : null;
    case "notice_line":
      return payload.noticeLine;
    case "qr_code":
      return null; // rendered as a QrCode below, not as text
  }
}

/** The printed token itself (ticket 27, FR-CFG-030..031): only the admin's enabled fields, in the admin's order. This
 * markup is unchanged by ticket 65 — only the `@media print` mechanism that isolates it (`.qms-print-slip`, shared
 * with the admin branding preview, theme.css) still relies on a plain class name, by design. */
function PrintSlip({ payload }: { payload: PrintPayload }) {
  const { t } = useI18n();
  return (
    <div style={payload.primaryColor ? { ["--qms-print-accent" as string]: payload.primaryColor } : undefined}>
      <div className="qms-print-slip-accent" />
      {payload.logoUrl && <img className="qms-print-slip-logo" src={payload.logoUrl} alt={t("kiosk.printSlip.logoAlt", { org: payload.orgName })} />}
      <p>
        <strong>{payload.orgName}</strong>
      </p>
      {payload.fields.map((field) => {
        if (field === "qr_code") {
          return <QrCode key={field} value={payload.qrValue} size={96} label={t("kiosk.result.qrLabel", { token: payload.tokenNumber })} />;
        }
        const value = printFieldValue(payload, field);
        if (!value) return null;
        if (field === "notice_line") return <p key={field}>{value}</p>;
        return (
          <p key={field}>
            {printFieldLabel(t, field)}: {value}
          </p>
        );
      })}
    </div>
  );
}

export interface KioskFlowProps {
  bootstrap: DeviceBootstrap;
  client: ApiClient;
  printer?: TokenPrinter;
  inactivityTimeoutMs?: number;
}

/**
 * The kiosk's whole common path (ticket 25, SRS §8.2), extended by ticket 26 with visitor identification and the
 * rest of the selection tree: idle with an optional language choice, Group → Service with single-option steps
 * skipped, identify (typed code, camera QR or mobile number, where the Service allows it, FR-ISS-013..014),
 * individual Agent and custom level (FR-ISS-010..012), confirm and print through {@link TokenPrinter}, and a
 * printer-failure fallback that still leaves the visitor with their Token and a way to follow it. `printer` and
 * `inactivityTimeoutMs` are the seams tests use; production leaves both at their defaults.
 */
export function KioskFlow({ bootstrap, client, printer, inactivityTimeoutMs = DEFAULT_INACTIVITY_TIMEOUT_MS }: KioskFlowProps) {
  // The visitor's on-screen pick is this session's "user" preference (highest priority in FR-I18N-003's chain) and
  // lives only in this component's memory — never persisted — so it is naturally gone the moment the flow resets to
  // idle (FR-I18N-004: the choice lasts only for the session). Nesting a second I18nProvider here, scoped to just
  // this flow, also lets the kiosk honour the paired site's own default language without touching ticket 24's
  // app-wide provider (which has no site to resolve against at all).
  const [languageOverride, setLanguageOverride] = useState<string | null>(null);

  return (
    <I18nProvider loadExtra={false} systemDefault={bootstrap.branding.default_language} userLanguage={languageOverride ?? undefined}>
      <KioskFlowInner
        bootstrap={bootstrap}
        client={client}
        printer={printer ?? DEFAULT_PRINTER}
        inactivityTimeoutMs={inactivityTimeoutMs}
        languageOverride={languageOverride}
        setLanguageOverride={setLanguageOverride}
      />
    </I18nProvider>
  );
}

const DEFAULT_PRINTER = new BrowserTokenPrinter();

interface InnerProps extends Required<Pick<KioskFlowProps, "bootstrap" | "client" | "inactivityTimeoutMs">> {
  printer: TokenPrinter;
  languageOverride: string | null;
  setLanguageOverride: (language: string | null) => void;
}

/** Which of the progress indicator's three stages a given step belongs to (ticket 65: "a progress indicator for the
 * steps"). The selection tree's exact length varies per bootstrap (identify/individual/custom are each skippable),
 * so the indicator tracks three fixed, always-meaningful stages rather than a step count that would jump around. */
function progressStage(step: Step): 1 | 2 | 3 | null {
  switch (step.kind) {
    case "idle":
    case "empty":
      return null;
    case "confirm":
      return 2;
    case "issuing":
    case "error":
    case "result":
      return 3;
    default:
      return 1;
  }
}

function ProgressIndicator({ stage }: { stage: 1 | 2 | 3 }) {
  const { t } = useI18n();
  const stages: { n: 1 | 2 | 3; label: string }[] = [
    { n: 1, label: t("kiosk.progress.choose") },
    { n: 2, label: t("kiosk.progress.confirm") },
    { n: 3, label: t("kiosk.progress.token") },
  ];
  return (
    <ol className="mx-auto flex w-full max-w-xl list-none gap-2 p-0" aria-label={t("kiosk.progress.label")}>
      {stages.map((s) => (
        <li
          key={s.n}
          aria-current={s.n === stage ? "step" : undefined}
          className={cn(
            "flex-1 rounded-full px-3 py-1.5 text-center text-sm font-semibold text-large:text-base",
            s.n === stage ? "bg-primary text-primary-fg" : s.n < stage ? "bg-primary/30 text-fg" : "bg-surface-muted text-fg-muted",
          )}
        >
          {s.label}
        </li>
      ))}
    </ol>
  );
}

/** Logo (if configured) plus the org or site name, brand-coloured (ticket 65). Shown once, above every screen. */
function BrandHeader({ bootstrap }: { bootstrap: DeviceBootstrap }) {
  const { t } = useI18n();
  const name = bootstrap.branding.org_name ?? bootstrap.branding.site_name;
  return (
    <div className="flex items-center justify-center gap-3">
      {bootstrap.branding.logo_url && (
        <img src={bootstrap.branding.logo_url} alt={t("kiosk.printSlip.logoAlt", { org: name })} className="max-h-12 max-w-28 object-contain" />
      )}
      <span className="text-lg font-semibold text-primary text-large:text-xl">{name}</span>
    </div>
  );
}

/** Every touch target on the flow is at least 64px tall (NFR-USA-003, FR-ISS-017); high-contrast adds a visible
 * border since colour alone stops being the only cue, and large-text grows both the box and the type further. */
const TILE_BASE =
  "flex min-h-[4rem] min-w-[3rem] items-center justify-center rounded-lg border border-border bg-surface px-6 py-4 text-center text-xl font-semibold text-fg shadow-sm motion-safe:transition-colors hover:bg-surface-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary disabled:cursor-not-allowed disabled:opacity-60 contrast-high:border-2 text-large:min-h-[5rem] text-large:text-2xl";
const TILE_LANG = "min-h-[6rem] text-3xl text-large:min-h-[7rem]";
const TILE_PRIMARY = "min-h-[5.5rem] bg-primary text-2xl text-primary-fg hover:bg-primary-hover active:bg-primary-active text-large:min-h-[6.5rem]";
const TILE_SECONDARY = "border-primary bg-transparent text-primary hover:bg-primary/10";

function Tile({ className, type = "button", ...props }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button type={type} className={cn(TILE_BASE, className)} {...props} />;
}

function TileGrid({ children }: { children: ReactNode }) {
  return <div className="grid w-full max-w-3xl grid-cols-[repeat(auto-fill,minmax(200px,1fr))] gap-4">{children}</div>;
}

function Screen({ children }: { children: ReactNode }) {
  return <div className="flex flex-1 flex-col items-center justify-center gap-6 text-center text-large:text-lg">{children}</div>;
}

function KioskFlowInner({ bootstrap, client, printer, inactivityTimeoutMs, languageOverride, setLanguageOverride }: InnerProps) {
  const { t, language } = useI18n();
  const [step, setStep] = useState<Step>({ kind: "idle" });
  const [accessibility, setAccessibility] = useState<AccessibilityPrefs>(() => loadAccessibilityPrefs());

  const groups = useMemo(
    () => groupsFromBootstrap(bootstrap, language, bootstrap.branding.default_language),
    [bootstrap, language],
  );
  const groupById = useMemo(() => new Map(groups.map((g) => [g.id, g])), [groups]);

  const goIdle = useCallback(() => {
    setLanguageOverride(null);
    setStep({ kind: "idle" });
  }, [setLanguageOverride]);

  // Inactivity (FR-ISS-015): any screen but idle returns here after the timeout, discarding the partial selection.
  useInactivityTimeout(step.kind !== "idle", inactivityTimeoutMs, goIdle);

  /**
   * After identification resolves, is skipped, or was never needed: the individual level, when the group offers it
   * (FR-ISS-012), else straight to the custom level or confirm. This is the one place every path down the tree
   * rejoins, whether the visitor tapped through group/service/identify or every one of those auto-skipped.
   */
  const afterIdentify = useCallback(
    async (selection: Selection) => {
      const group = groupById.get(selection.groupId);
      if (!group) {
        setStep({ kind: "confirm", selection });
        return;
      }
      if (group.teamSelectable && group.individualSelectable) {
        try {
          const agents = await client.kiosk.agentsOf(group.id);
          if (agents.items.length > 0) {
            setStep({ kind: "individual", selection, agents: agents.items });
            return;
          }
        } catch {
          // No queue action may stall on this (same spirit as FR-INT-013): if the on-duty list cannot be fetched,
          // the visitor simply is not offered a specific Agent, rather than being stuck.
        }
      }
      setStep(customOrConfirmStep(selection, group));
    },
    [client, groupById],
  );

  /** FR-CFG-013: the identify step appears only when the chosen Service wants it, and is skippable unless mandatory; otherwise the tree continues straight past it. */
  const afterServiceChosen = useCallback(
    async (selection: Selection) => {
      const group = groupById.get(selection.groupId);
      const service = group?.services.find((s) => s.id === selection.serviceId);
      const identifier = service?.visitorIdentifier ?? "not_required";
      if (identifier === "not_required") {
        await afterIdentify(selection);
        return;
      }
      setStep({ kind: "identify", selection, mandatory: identifier === "mandatory" });
    },
    [afterIdentify, groupById],
  );

  /** Steps that resolve to a single option are skipped automatically (§8.2): one Service in a group means the visitor never sees that pick. */
  const enterGroup = useCallback(
    async (group: GroupVM) => {
      if (group.services.length > 1) {
        setStep({ kind: "service", groupId: group.id, groupName: group.name });
        return;
      }
      const service = group.services[0]!;
      await afterServiceChosen({ groupId: group.id, groupName: group.name, serviceId: service.id, serviceName: service.name });
    },
    [afterServiceChosen],
  );

  async function leaveIdle(pickedLanguage: string | null) {
    if (pickedLanguage) setLanguageOverride(pickedLanguage);
    if (groups.length === 0) {
      setStep({ kind: "empty" });
      return;
    }
    if (groups.length > 1) {
      setStep({ kind: "group" });
      return;
    }
    await enterGroup(groups[0]!);
  }

  function pickGroup(groupId: string) {
    const group = groupById.get(groupId);
    if (group) void enterGroup(group);
  }

  function pickService(groupId: string, groupName: string, serviceId: string) {
    const group = groupById.get(groupId);
    const service = group?.services.find((s) => s.id === serviceId);
    if (!group || !service) return;
    void afterServiceChosen({ groupId, groupName, serviceId, serviceName: service.name });
  }

  function skipIdentify(current: Extract<Step, { kind: "identify" }>) {
    void afterIdentify(current.selection);
  }

  function identified(current: { selection: Selection; mandatory: boolean }, identity: { visitorId: string; name: string; category: string | null }) {
    void afterIdentify({ ...current.selection, visitorId: identity.visitorId, visitorName: identity.name, visitorCategory: identity.category });
  }

  function afterIndividual(current: Extract<Step, { kind: "individual" }>, agent: KioskAgentOption | null) {
    const group = groupById.get(current.selection.groupId);
    const selection = agent ? { ...current.selection, agentId: agent.agent_id, agentName: agent.name } : current.selection;
    setStep(group ? customOrConfirmStep(selection, group) : { kind: "confirm", selection });
  }

  function afterCustom(current: Extract<Step, { kind: "custom" }>, option: CustomLevelOptionVM | null) {
    const selection = option ? { ...current.selection, customLevelId: option.id, customLevelName: option.name } : current.selection;
    setStep({ kind: "confirm", selection });
  }

  const issue = useCallback(
    async (selection: Selection, idempotencyKey: string) => {
      setStep({ kind: "issuing", selection, idempotencyKey });
      try {
        const ticket = await client.tickets.issueKiosk(
          {
            service_id: selection.serviceId,
            visitor_id: selection.visitorId,
            agent_id: selection.agentId,
            custom_level_id: selection.customLevelId,
          },
          idempotencyKey,
        );
        let printFailed = false;
        try {
          await printer.print(printPayloadFor(ticket, selection, bootstrap));
        } catch {
          printFailed = true;
        }
        setStep({ kind: "result", ticket, selection, printFailed });
      } catch (cause) {
        const message = cause instanceof ApiRequestError ? localisedApiError(cause, language, t) : t("errors.network_error");
        setStep({ kind: "error", selection, idempotencyKey, message });
      }
    },
    [client, printer, language, t, bootstrap],
  );

  function confirmAndPrint(selection: Selection) {
    void issue(selection, newIdempotencyKey());
  }

  function retry(current: Extract<Step, { kind: "error" }>) {
    void issue(current.selection, current.idempotencyKey);
  }

  // The organisation's primary colour (ticket 27, FR-CFG-030) overrides the kiosk's own accent everywhere `bg-primary`/
  // `text-primary` is used (tiles, the progress indicator, the brand header), high-contrast mode's own palette
  // excepted: it is set as an attribute-selector rule on this same element, so it always beats this inline style —
  // see theme.css.
  const brand = bootstrap.branding.primary_color ? deriveBrandColors(bootstrap.branding.primary_color) : null;
  const brandStyle = brand
    ? ({
        ["--qms-raw-primary" as string]: brand.primary,
        ["--qms-raw-primary-hover" as string]: brand.primaryHover,
        ["--qms-raw-primary-active" as string]: brand.primaryActive,
        ["--qms-raw-primary-fg" as string]: brand.primaryFg,
      } as CSSProperties)
    : undefined;

  const stage = progressStage(step);

  return (
    <main
      className="flex min-h-dvh flex-col gap-4 bg-surface-muted p-6 text-fg"
      data-contrast={accessibility.highContrast ? "high" : "normal"}
      data-text={accessibility.largeText ? "large" : "normal"}
      style={brandStyle}
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <BrandHeader bootstrap={bootstrap} />
        <AccessibilityBar prefs={accessibility} onChange={(next) => { setAccessibility(next); saveAccessibilityPrefs(next); }} />
      </div>
      {stage && <ProgressIndicator stage={stage} />}
      {step.kind === "idle" && <IdleScreen languages={bootstrap.languages} onStart={leaveIdle} />}
      {step.kind === "empty" && <EmptyScreen onBack={goIdle} />}
      {step.kind === "group" && <GroupScreen groups={groups} onPick={pickGroup} onBack={goIdle} />}
      {step.kind === "service" && (
        <ServiceScreen
          group={groupById.get(step.groupId)}
          onPick={(serviceId) => pickService(step.groupId, step.groupName, serviceId)}
          onBack={goIdle}
        />
      )}
      {step.kind === "identify" && (
        <IdentifyScreen
          mandatory={step.mandatory}
          onEnter={(method) => setStep({ kind: "identifyEnter", selection: step.selection, mandatory: step.mandatory, method })}
          onScan={() => setStep({ kind: "identifyScan", selection: step.selection, mandatory: step.mandatory })}
          onSkip={() => skipIdentify(step)}
          onBack={goIdle}
        />
      )}
      {step.kind === "identifyEnter" && (
        <IdentifyEnterScreen
          client={client}
          method={step.method}
          onFound={(identity) => identified(step, identity)}
          onBack={() => setStep({ kind: "identify", selection: step.selection, mandatory: step.mandatory })}
        />
      )}
      {step.kind === "identifyScan" && (
        <IdentifyScanScreen
          client={client}
          onFound={(identity) => identified(step, identity)}
          onCancel={() => setStep({ kind: "identify", selection: step.selection, mandatory: step.mandatory })}
        />
      )}
      {step.kind === "individual" && (
        <IndividualScreen agents={step.agents} onPick={(agent) => afterIndividual(step, agent)} onBack={goIdle} />
      )}
      {step.kind === "custom" && <CustomLevelScreen options={step.options} onPick={(option) => afterCustom(step, option)} onBack={goIdle} />}
      {step.kind === "confirm" && <ConfirmScreen selection={step.selection} onConfirm={() => confirmAndPrint(step.selection)} onBack={goIdle} />}
      {step.kind === "issuing" && <IssuingScreen />}
      {step.kind === "error" && <ErrorScreen message={step.message} onRetry={() => retry(step)} onStartOver={goIdle} />}
      {step.kind === "result" && (
        <ResultScreen ticket={step.ticket} selection={step.selection} bootstrap={bootstrap} printFailed={step.printFailed} onDone={goIdle} />
      )}
    </main>
  );
}

function localisedApiError(cause: ApiRequestError, language: string, t: (key: string, params?: Record<string, string | number>) => string): string {
  return cause.body?.message_i18n?.[language] ?? t(`errors.${cause.code}`);
}

function AccessibilityBar({ prefs, onChange }: { prefs: AccessibilityPrefs; onChange: (next: AccessibilityPrefs) => void }) {
  const { t } = useI18n();
  return (
    <div className="flex justify-end gap-2">
      <Button
        type="button"
        variant={prefs.highContrast ? "primary" : "secondary"}
        aria-pressed={prefs.highContrast}
        onClick={() => onChange({ ...prefs, highContrast: !prefs.highContrast })}
      >
        {t("kiosk.accessibility.highContrast")}
      </Button>
      <Button
        type="button"
        variant={prefs.largeText ? "primary" : "secondary"}
        aria-pressed={prefs.largeText}
        onClick={() => onChange({ ...prefs, largeText: !prefs.largeText })}
      >
        {t("kiosk.accessibility.largeText")}
      </Button>
    </div>
  );
}

function IdleScreen({ languages, onStart }: { languages: string[]; onStart: (language: string | null) => void }) {
  const { t } = useI18n();
  return (
    <Screen>
      <h1 className="text-3xl font-bold text-large:text-4xl">{t("kiosk.idle.title")}</h1>
      {languages.length > 1 ? (
        <>
          <p className="text-fg-muted">{t("kiosk.idle.chooseLanguage")}</p>
          <TileGrid>
            {languages.map((code) => (
              <Tile key={code} className={TILE_LANG} onClick={() => onStart(code)}>
                {t(`kiosk.language.native.${code}`)}
              </Tile>
            ))}
          </TileGrid>
        </>
      ) : (
        <Tile className={TILE_LANG} onClick={() => onStart(null)}>
          {t("kiosk.idle.tapToStart")}
        </Tile>
      )}
    </Screen>
  );
}

function EmptyScreen({ onBack }: { onBack: () => void }) {
  const { t } = useI18n();
  return (
    <Screen>
      <p className="text-fg-muted">{t("kiosk.group.empty")}</p>
      <Tile onClick={onBack}>{t("kiosk.back")}</Tile>
    </Screen>
  );
}

function GroupScreen({ groups, onPick, onBack }: { groups: GroupVM[]; onPick: (id: string) => void; onBack: () => void }) {
  const { t } = useI18n();
  return (
    <Screen>
      <h1 className="text-3xl font-bold text-large:text-4xl">{t("kiosk.group.title")}</h1>
      <TileGrid>
        {groups.map((group) => (
          <Tile key={group.id} onClick={() => onPick(group.id)}>
            {group.name}
          </Tile>
        ))}
      </TileGrid>
      <Tile className={TILE_SECONDARY} onClick={onBack}>
        {t("kiosk.back")}
      </Tile>
    </Screen>
  );
}

function ServiceScreen({ group, onPick, onBack }: { group: GroupVM | undefined; onPick: (id: string) => void; onBack: () => void }) {
  const { t } = useI18n();
  return (
    <Screen>
      <h1 className="text-3xl font-bold text-large:text-4xl">{t("kiosk.service.title")}</h1>
      <TileGrid>
        {group?.services.map((service) => (
          <Tile key={service.id} onClick={() => onPick(service.id)}>
            {service.name}
          </Tile>
        ))}
      </TileGrid>
      <Tile className={TILE_SECONDARY} onClick={onBack}>
        {t("kiosk.back")}
      </Tile>
    </Screen>
  );
}

/** FR-ISS-013: typed code, camera QR (where the browser supports it) or mobile number; skippable unless {@code mandatory}. */
function IdentifyScreen({
  mandatory,
  onEnter,
  onScan,
  onSkip,
  onBack,
}: {
  mandatory: boolean;
  onEnter: (method: IdentifyMethod) => void;
  onScan: () => void;
  onSkip: () => void;
  onBack: () => void;
}) {
  const { t } = useI18n();
  const canScan = useMemo(() => qrScanningSupported(), []);
  return (
    <Screen>
      <h1 className="text-3xl font-bold text-large:text-4xl">{t("kiosk.identify.title")}</h1>
      <p className="text-fg-muted">{t(mandatory ? "kiosk.identify.mandatoryPrompt" : "kiosk.identify.optionalPrompt")}</p>
      <TileGrid>
        <Tile onClick={() => onEnter("code")}>{t("kiosk.identify.byCode")}</Tile>
        <Tile onClick={() => onEnter("phone")}>{t("kiosk.identify.byPhone")}</Tile>
        {canScan && <Tile onClick={onScan}>{t("kiosk.identify.byQr")}</Tile>}
      </TileGrid>
      {!mandatory && <Tile onClick={onSkip}>{t("kiosk.identify.skip")}</Tile>}
      <Tile className={TILE_SECONDARY} onClick={onBack}>
        {t("kiosk.back")}
      </Tile>
    </Screen>
  );
}

interface IdentifiedVisitor {
  visitorId: string;
  name: string;
  category: string | null;
}

/** A typed code or mobile number (FR-ISS-013); {@code GET /kiosk/visitors/identify} answers only name and category (FR-ISS-014). */
function IdentifyEnterScreen({
  client,
  method,
  onFound,
  onBack,
}: {
  client: ApiClient;
  method: IdentifyMethod;
  onFound: (identity: IdentifiedVisitor) => void;
  onBack: () => void;
}) {
  const { t } = useI18n();
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!value.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      const identity = await client.kiosk.identify(value.trim());
      onFound({ visitorId: identity.visitor_id, name: identity.name, category: identity.category });
    } catch {
      setError(t("kiosk.identify.notFound"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Screen>
      <h1 className="text-3xl font-bold text-large:text-4xl">{t(method === "code" ? "kiosk.identify.byCode" : "kiosk.identify.byPhone")}</h1>
      <form onSubmit={submit} className="flex w-full max-w-md flex-col gap-4">
        <TextField
          id="kiosk-identify-value"
          label={t(method === "code" ? "kiosk.identify.codeLabel" : "kiosk.identify.phoneLabel")}
          className="w-full"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          inputMode={method === "phone" ? "tel" : "text"}
          autoFocus
        />
        {error && <ErrorAlert>{error}</ErrorAlert>}
        <Tile className={TILE_PRIMARY} type="submit" disabled={busy || !value.trim()}>
          {t(busy ? "kiosk.identify.lookingUp" : "kiosk.identify.submit")}
        </Tile>
      </form>
      <Tile className={TILE_SECONDARY} onClick={onBack}>
        {t("kiosk.back")}
      </Tile>
    </Screen>
  );
}

/**
 * Camera-based QR identification (FR-ISS-013), through the browser's own `BarcodeDetector` (SRS §28.1's assumption
 * of a camera capable of reading a QR code in the browser) — no peripheral driver or third-party package. The
 * "Scan QR" tile that leads here is itself hidden when the API is unavailable (see {@link qrScanningSupported}),
 * so this component only ever mounts where scanning can actually work.
 */
function IdentifyScanScreen({
  client,
  onFound,
  onCancel,
}: {
  client: ApiClient;
  onFound: (identity: IdentifiedVisitor) => void;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const videoRef = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState<string | null>(null);
  const lookingUp = useRef(false);

  useEffect(() => {
    let stream: MediaStream | null = null;
    let timer: ReturnType<typeof setInterval> | null = null;
    let cancelled = false;

    async function start() {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } });
        if (cancelled) {
          stream.getTracks().forEach((track) => track.stop());
          return;
        }
        const video = videoRef.current;
        if (video) {
          video.srcObject = stream;
          await video.play();
        }
        const Detector = (window as unknown as { BarcodeDetector: new (opts: { formats: string[] }) => { detect(source: unknown): Promise<{ rawValue: string }[]> } }).BarcodeDetector;
        const detector = new Detector({ formats: ["qr_code"] });
        timer = setInterval(async () => {
          if (cancelled || lookingUp.current || !videoRef.current) return;
          try {
            const codes = await detector.detect(videoRef.current);
            const code = codes[0];
            if (code) {
              lookingUp.current = true;
              try {
                const identity = await client.kiosk.identify(code.rawValue);
                if (!cancelled) onFound({ visitorId: identity.visitor_id, name: identity.name, category: identity.category });
              } catch {
                if (!cancelled) setError(t("kiosk.identify.notFound"));
              } finally {
                lookingUp.current = false;
              }
            }
          } catch {
            // A frame with nothing decodable is not an error; keep scanning.
          }
        }, 300);
      } catch {
        if (!cancelled) setError(t("kiosk.identify.scanUnsupported"));
      }
    }

    void start();
    return () => {
      cancelled = true;
      if (timer) clearInterval(timer);
      stream?.getTracks().forEach((track) => track.stop());
    };
  }, [client, onFound, t]);

  return (
    <Screen>
      <h1 className="text-3xl font-bold text-large:text-4xl">{t("kiosk.identify.scanTitle")}</h1>
      {/* eslint-disable-next-line jsx-a11y/media-has-caption -- a live camera preview, not recorded media */}
      <video ref={videoRef} className="mx-auto aspect-square w-[min(90vw,24rem)] rounded-lg bg-black object-cover" muted playsInline aria-hidden="true" />
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <Tile className={TILE_SECONDARY} onClick={onCancel}>
        {t("kiosk.back")}
      </Tile>
    </Screen>
  );
}

function IndividualScreen({
  agents,
  onPick,
  onBack,
}: {
  agents: KioskAgentOption[];
  onPick: (agent: KioskAgentOption | null) => void;
  onBack: () => void;
}) {
  const { t } = useI18n();
  return (
    <Screen>
      <h1 className="text-3xl font-bold text-large:text-4xl">{t("kiosk.individual.title")}</h1>
      <TileGrid>
        <Tile onClick={() => onPick(null)}>{t("kiosk.individual.anyone")}</Tile>
        {agents.map((agent) => (
          <Tile key={agent.agent_id} onClick={() => onPick(agent)}>
            {agent.name}
            {agent.queue_longer_than_group && <span className="text-danger text-[0.85em]"> — {t("kiosk.individual.queueWarning")}</span>}
          </Tile>
        ))}
      </TileGrid>
      <Tile className={TILE_SECONDARY} onClick={onBack}>
        {t("kiosk.back")}
      </Tile>
    </Screen>
  );
}

function CustomLevelScreen({
  options,
  onPick,
  onBack,
}: {
  options: CustomLevelOptionVM[];
  onPick: (option: CustomLevelOptionVM | null) => void;
  onBack: () => void;
}) {
  const { t } = useI18n();
  return (
    <Screen>
      <TileGrid>
        {options.map((option) => (
          <Tile key={option.id} onClick={() => onPick(option)}>
            {option.name}
          </Tile>
        ))}
      </TileGrid>
      <Tile onClick={() => onPick(null)}>{t("kiosk.custom.skip")}</Tile>
      <Tile className={TILE_SECONDARY} onClick={onBack}>
        {t("kiosk.back")}
      </Tile>
    </Screen>
  );
}

function ConfirmScreen({
  selection,
  onConfirm,
  onBack,
}: {
  selection: Selection;
  onConfirm: () => void;
  onBack: () => void;
}) {
  const { t } = useI18n();
  return (
    <Screen>
      <h1 className="text-3xl font-bold text-large:text-4xl">{t("kiosk.confirm.title")}</h1>
      <div className="flex flex-col gap-1 text-lg text-large:text-xl">
        <p>
          {t("kiosk.confirm.groupLabel")}: {selection.groupName}
        </p>
        <p>
          {t("kiosk.confirm.serviceLabel")}: {selection.serviceName}
        </p>
        {selection.visitorName && (
          <p>
            {t("kiosk.confirm.visitorLabel")}: {selection.visitorName}
            {selection.visitorCategory ? ` (${selection.visitorCategory})` : ""}
          </p>
        )}
        {selection.agentName && (
          <p>
            {t("kiosk.confirm.agentLabel")}: {selection.agentName}
          </p>
        )}
        {selection.customLevelName && <p>{selection.customLevelName}</p>}
      </div>
      <Tile className={TILE_PRIMARY} onClick={onConfirm}>
        {t("kiosk.confirm.print")}
      </Tile>
      <Tile className={TILE_SECONDARY} onClick={onBack}>
        {t("kiosk.back")}
      </Tile>
    </Screen>
  );
}

function IssuingScreen() {
  const { t } = useI18n();
  return (
    <Screen>
      <p role="status" className="text-xl">
        {t("kiosk.confirm.issuing")}
      </p>
    </Screen>
  );
}

function ErrorScreen({ message, onRetry, onStartOver }: { message: string; onRetry: () => void; onStartOver: () => void }) {
  const { t } = useI18n();
  return (
    <Screen>
      <h1 className="text-3xl font-bold text-large:text-4xl">{t("kiosk.error.title")}</h1>
      <ErrorAlert>{message}</ErrorAlert>
      <Tile onClick={onRetry}>{t("kiosk.error.tryAgain")}</Tile>
      <Tile className={TILE_SECONDARY} onClick={onStartOver}>
        {t("kiosk.error.startOver")}
      </Tile>
    </Screen>
  );
}

function ResultScreen({
  ticket,
  selection,
  bootstrap,
  printFailed,
  onDone,
}: {
  ticket: Ticket;
  selection: Selection;
  bootstrap: DeviceBootstrap;
  printFailed: boolean;
  onDone: () => void;
}) {
  const { t, formatToken } = useI18n();
  const statusUrl = useMemo(() => ticketStatusUrl(ticket), [ticket]);
  // Token numbers are always Western Arabic digits, on every surface, never the reading language's own numerals
  // (FR-I18N-020, ADR-0011) — `formatToken` is the one place that rule is enforced, so every render of it goes through it.
  const token = formatToken(ticket.token_number);
  const payload = useMemo(() => printPayloadFor(ticket, selection, bootstrap), [ticket, selection, bootstrap]);
  return (
    <Screen>
      <h1 className="text-3xl font-bold text-large:text-4xl">{t(printFailed ? "kiosk.result.printFailedTitle" : "kiosk.result.printedTitle")}</h1>
      <p className="text-[3rem] font-bold tracking-wide tabular-nums text-large:text-[4.5rem]">{token}</p>
      {printFailed && (
        <>
          <p>{t("kiosk.result.printFailedBody")}</p>
          <QrCode value={statusUrl} label={t("kiosk.result.qrLabel", { token })} />
        </>
      )}
      {!printFailed && <p>{t("kiosk.result.printedBody", { token })}</p>}
      <Tile onClick={onDone}>{t("kiosk.result.done")}</Tile>
      {/* The actual printed token (ticket 27, FR-CFG-030..031): invisible on screen, the only thing `window.print()` shows. */}
      <div className="qms-print-slip" aria-hidden="true">
        <PrintSlip payload={payload} />
      </div>
    </Screen>
  );
}
