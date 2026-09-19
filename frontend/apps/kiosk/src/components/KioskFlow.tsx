"use client";

import { ApiRequestError, newIdempotencyKey, type ApiClient, type DeviceBootstrap, type Ticket } from "@qms/api-client";
import { I18nProvider, useI18n } from "@qms/i18n/react";
import { ErrorAlert, QrCode } from "@qms/ui";
import { useCallback, useEffect, useMemo, useState } from "react";
import { DEFAULT_INACTIVITY_TIMEOUT_MS, useInactivityTimeout } from "../lib/inactivity";
import { localisedName } from "../lib/localised-name";
import { BrowserTokenPrinter, type TokenPrinter } from "../lib/token-printer";

interface GroupVM {
  id: string;
  name: string;
  services: { id: string; name: string }[];
}

type Step =
  | { kind: "idle" }
  | { kind: "empty" }
  | { kind: "group" }
  | { kind: "service"; groupId: string; groupName: string }
  | { kind: "confirm"; groupId: string; groupName: string; serviceId: string; serviceName: string }
  | { kind: "issuing"; groupId: string; groupName: string; serviceId: string; serviceName: string; idempotencyKey: string }
  | { kind: "result"; ticket: Ticket; printFailed: boolean }
  | { kind: "error"; groupId: string; groupName: string; serviceId: string; serviceName: string; idempotencyKey: string; message: string };

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
      services: group.services.map((service) => ({ id: service.id, name: localisedName(service.name_i18n, language, defaultLanguage) })),
    }));
}

/** Steps that resolve to a single option are skipped automatically (§8.2): one group and/or one Service in it means the visitor never sees that pick. */
function entryStep(groups: GroupVM[]): Step {
  if (groups.length === 0) return { kind: "empty" };
  if (groups.length > 1) return { kind: "group" };
  return serviceOrConfirmStep(groups[0]!);
}

function serviceOrConfirmStep(group: GroupVM): Step {
  if (group.services.length > 1) return { kind: "service", groupId: group.id, groupName: group.name };
  const service = group.services[0]!;
  return { kind: "confirm", groupId: group.id, groupName: group.name, serviceId: service.id, serviceName: service.name };
}

/** The URL the printer-failure QR opens (FR-ISS-016): the visitor's mobile ticket-status page (ticket 37), same origin as this kiosk (ADR-0012). */
function ticketStatusUrl(ticket: Ticket): string {
  const origin = typeof window === "undefined" ? "" : window.location.origin;
  const params = new URLSearchParams({ t: ticket.id, s: ticket.secret ?? "" });
  return `${origin}/visitor/?${params.toString()}`;
}

export interface KioskFlowProps {
  bootstrap: DeviceBootstrap;
  client: ApiClient;
  printer?: TokenPrinter;
  inactivityTimeoutMs?: number;
}

/**
 * The kiosk's whole common path (ticket 25, SRS §8.2): idle with an optional language choice, Group → Service with
 * single-option steps skipped, confirm and print through {@link TokenPrinter}, and a printer-failure fallback that
 * still leaves the visitor with their Token and a way to follow it. `printer` and `inactivityTimeoutMs` are the
 * seams tests use; production leaves both at their defaults.
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

  function leaveIdle(pickedLanguage: string | null) {
    if (pickedLanguage) setLanguageOverride(pickedLanguage);
    setStep(entryStep(groups));
  }

  function pickGroup(groupId: string) {
    const group = groupById.get(groupId);
    if (group) setStep(serviceOrConfirmStep(group));
  }

  function pickService(groupId: string, groupName: string, serviceId: string) {
    const service = groupById.get(groupId)?.services.find((s) => s.id === serviceId);
    if (service) setStep({ kind: "confirm", groupId, groupName, serviceId, serviceName: service.name });
  }

  const issue = useCallback(
    async (groupId: string, groupName: string, serviceId: string, serviceName: string, idempotencyKey: string) => {
      setStep({ kind: "issuing", groupId, groupName, serviceId, serviceName, idempotencyKey });
      try {
        const ticket = await client.tickets.issueKiosk({ service_id: serviceId }, idempotencyKey);
        let printFailed = false;
        try {
          await printer.print({ tokenNumber: ticket.token_number, serviceName, groupName });
        } catch {
          printFailed = true;
        }
        setStep({ kind: "result", ticket, printFailed });
      } catch (cause) {
        const message = cause instanceof ApiRequestError ? localisedApiError(cause, language, t) : t("errors.network_error");
        setStep({ kind: "error", groupId, groupName, serviceId, serviceName, idempotencyKey, message });
      }
    },
    [client, printer, language, t],
  );

  function confirmAndPrint(groupId: string, groupName: string, serviceId: string, serviceName: string) {
    void issue(groupId, groupName, serviceId, serviceName, newIdempotencyKey());
  }

  function retry(current: Extract<Step, { kind: "error" }>) {
    void issue(current.groupId, current.groupName, current.serviceId, current.serviceName, current.idempotencyKey);
  }

  return (
    <div className={`qms-kiosk${accessibility.largeText ? " qms-kiosk--large-text" : ""}`} data-contrast={accessibility.highContrast ? "high" : "normal"}>
      <AccessibilityBar prefs={accessibility} onChange={(next) => { setAccessibility(next); saveAccessibilityPrefs(next); }} />
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
      {step.kind === "confirm" && (
        <ConfirmScreen
          groupName={step.groupName}
          serviceName={step.serviceName}
          onConfirm={() => confirmAndPrint(step.groupId, step.groupName, step.serviceId, step.serviceName)}
          onBack={goIdle}
        />
      )}
      {step.kind === "issuing" && <IssuingScreen />}
      {step.kind === "error" && <ErrorScreen message={step.message} onRetry={() => retry(step)} onStartOver={goIdle} />}
      {step.kind === "result" && <ResultScreen ticket={step.ticket} printFailed={step.printFailed} onDone={goIdle} />}
    </div>
  );
}

function localisedApiError(cause: ApiRequestError, language: string, t: (key: string, params?: Record<string, string | number>) => string): string {
  return cause.body?.message_i18n?.[language] ?? t(`errors.${cause.code}`);
}

function AccessibilityBar({ prefs, onChange }: { prefs: AccessibilityPrefs; onChange: (next: AccessibilityPrefs) => void }) {
  const { t } = useI18n();
  return (
    <div className="qms-kiosk-a11y-bar">
      <button
        type="button"
        className="qms-kiosk-tile qms-kiosk-a11y-toggle"
        aria-pressed={prefs.highContrast}
        onClick={() => onChange({ ...prefs, highContrast: !prefs.highContrast })}
      >
        {t("kiosk.accessibility.highContrast")}
      </button>
      <button
        type="button"
        className="qms-kiosk-tile qms-kiosk-a11y-toggle"
        aria-pressed={prefs.largeText}
        onClick={() => onChange({ ...prefs, largeText: !prefs.largeText })}
      >
        {t("kiosk.accessibility.largeText")}
      </button>
    </div>
  );
}

function IdleScreen({ languages, onStart }: { languages: string[]; onStart: (language: string | null) => void }) {
  const { t } = useI18n();
  return (
    <div className="qms-kiosk-screen qms-kiosk-idle">
      <h1 className="qms-heading">{t("kiosk.idle.title")}</h1>
      {languages.length > 1 ? (
        <>
          <p className="qms-muted">{t("kiosk.idle.chooseLanguage")}</p>
          <div className="qms-kiosk-grid">
            {languages.map((code) => (
              <button key={code} type="button" className="qms-kiosk-tile qms-kiosk-tile--lang" onClick={() => onStart(code)}>
                {t(`kiosk.language.native.${code}`)}
              </button>
            ))}
          </div>
        </>
      ) : (
        <button type="button" className="qms-kiosk-tile qms-kiosk-tile--lang" onClick={() => onStart(null)}>
          {t("kiosk.idle.tapToStart")}
        </button>
      )}
    </div>
  );
}

function EmptyScreen({ onBack }: { onBack: () => void }) {
  const { t } = useI18n();
  return (
    <div className="qms-kiosk-screen">
      <p className="qms-muted">{t("kiosk.group.empty")}</p>
      <button type="button" className="qms-button qms-kiosk-tile" onClick={onBack}>
        {t("kiosk.back")}
      </button>
    </div>
  );
}

function GroupScreen({ groups, onPick, onBack }: { groups: GroupVM[]; onPick: (id: string) => void; onBack: () => void }) {
  const { t } = useI18n();
  return (
    <div className="qms-kiosk-screen">
      <h1 className="qms-heading">{t("kiosk.group.title")}</h1>
      <div className="qms-kiosk-grid">
        {groups.map((group) => (
          <button key={group.id} type="button" className="qms-kiosk-tile" onClick={() => onPick(group.id)}>
            {group.name}
          </button>
        ))}
      </div>
      <button type="button" className="qms-button qms-button--secondary qms-kiosk-tile" onClick={onBack}>
        {t("kiosk.back")}
      </button>
    </div>
  );
}

function ServiceScreen({ group, onPick, onBack }: { group: GroupVM | undefined; onPick: (id: string) => void; onBack: () => void }) {
  const { t } = useI18n();
  return (
    <div className="qms-kiosk-screen">
      <h1 className="qms-heading">{t("kiosk.service.title")}</h1>
      <div className="qms-kiosk-grid">
        {group?.services.map((service) => (
          <button key={service.id} type="button" className="qms-kiosk-tile" onClick={() => onPick(service.id)}>
            {service.name}
          </button>
        ))}
      </div>
      <button type="button" className="qms-button qms-button--secondary qms-kiosk-tile" onClick={onBack}>
        {t("kiosk.back")}
      </button>
    </div>
  );
}

function ConfirmScreen({
  groupName,
  serviceName,
  onConfirm,
  onBack,
}: {
  groupName: string;
  serviceName: string;
  onConfirm: () => void;
  onBack: () => void;
}) {
  const { t } = useI18n();
  return (
    <div className="qms-kiosk-screen">
      <h1 className="qms-heading">{t("kiosk.confirm.title")}</h1>
      <p>
        {t("kiosk.confirm.groupLabel")}: {groupName}
      </p>
      <p>
        {t("kiosk.confirm.serviceLabel")}: {serviceName}
      </p>
      <button type="button" className="qms-button qms-kiosk-tile qms-kiosk-tile--primary" onClick={onConfirm}>
        {t("kiosk.confirm.print")}
      </button>
      <button type="button" className="qms-button qms-button--secondary qms-kiosk-tile" onClick={onBack}>
        {t("kiosk.back")}
      </button>
    </div>
  );
}

function IssuingScreen() {
  const { t } = useI18n();
  return (
    <div className="qms-kiosk-screen" role="status">
      <p>{t("kiosk.confirm.issuing")}</p>
    </div>
  );
}

function ErrorScreen({ message, onRetry, onStartOver }: { message: string; onRetry: () => void; onStartOver: () => void }) {
  const { t } = useI18n();
  return (
    <div className="qms-kiosk-screen">
      <h1 className="qms-heading">{t("kiosk.error.title")}</h1>
      <ErrorAlert>{message}</ErrorAlert>
      <button type="button" className="qms-button qms-kiosk-tile" onClick={onRetry}>
        {t("kiosk.error.tryAgain")}
      </button>
      <button type="button" className="qms-button qms-button--secondary qms-kiosk-tile" onClick={onStartOver}>
        {t("kiosk.error.startOver")}
      </button>
    </div>
  );
}

function ResultScreen({ ticket, printFailed, onDone }: { ticket: Ticket; printFailed: boolean; onDone: () => void }) {
  const { t, language, formatToken } = useI18n();
  const statusUrl = useMemo(() => ticketStatusUrl(ticket), [ticket]);
  // Token numbers are always Western Arabic digits, on every surface, never the reading language's own numerals
  // (FR-I18N-020, ADR-0011) — `formatToken` is the one place that rule is enforced, so every render of it goes through it.
  const token = formatToken(ticket.token_number);
  const serviceName = localisedName(ticket.service.name_i18n, language, language);
  return (
    <div className="qms-kiosk-screen">
      <h1 className="qms-heading">{t(printFailed ? "kiosk.result.printFailedTitle" : "kiosk.result.printedTitle")}</h1>
      <p className="qms-token">{token}</p>
      {printFailed && (
        <>
          <p>{t("kiosk.result.printFailedBody")}</p>
          <QrCode value={statusUrl} label={t("kiosk.result.qrLabel", { token })} />
        </>
      )}
      {!printFailed && <p>{t("kiosk.result.printedBody", { token })}</p>}
      <button type="button" className="qms-button qms-kiosk-tile" onClick={onDone}>
        {t("kiosk.result.done")}
      </button>
      <div className="qms-print-slip" aria-hidden="true">
        <p className="qms-token">{token}</p>
        <p>{serviceName}</p>
      </div>
    </div>
  );
}
