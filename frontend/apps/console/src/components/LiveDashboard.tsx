"use client";

import type { Alert, DashboardFilter, DashboardSnapshot } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, TextField } from "@qms/ui";
import { useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useAuth } from "../lib/auth";
import { describeError, localisedName } from "../lib/console-support";
import { useTopics } from "../lib/realtime";
import { useApi } from "../lib/runtime";

const FILTER_KEYS = ["site_id", "zone_id", "service_group_id", "service_id", "priority_class_id"] as const;

/** The filter a live view is under, read straight off the URL's own query string (FR-MON-002: shareable as a URL). A
 * Site with no explicit `site_id` falls back to the caller's own first Site claim, so the screen still opens for
 * someone who has not yet chosen or been sent a link. */
function filterFrom(params: URLSearchParams, fallbackSiteId: string | null): DashboardFilter | null {
  const siteId = params.get("site_id") ?? fallbackSiteId;
  if (!siteId) return null;
  const filter: Record<string, string> = { site_id: siteId };
  for (const key of FILTER_KEYS) {
    if (key === "site_id") continue;
    const value = params.get(key);
    if (value) filter[key] = value;
  }
  return filter as unknown as DashboardFilter;
}

/**
 * The supervisor's live dashboard (SRS §15.1, ticket 46): every FR-MON-003 tile, filterable by Site, Zone, Service
 * group, Service and Priority class and shareable as this page's own URL (FR-MON-002), refreshed through the
 * realtime channel rather than polling (FR-MON-001) — `site:{id}:dashboard` carries only a refresh signal, so every
 * signal (and the first load) re-asks the caller's own scoped `GET /dashboard/live`, never trusts a broadcast that
 * could not be scoped to their own reach (FR-CFG-105). Acts from it (FR-MON-004): re-prioritise a waiting ticket,
 * force-close a counter, change an Agent's status — each the same endpoint an earlier ticket already built and
 * permission-checked — and send a staff alert, the one action this ticket adds.
 */
export function LiveDashboard() {
  const { t, formatNumber } = useI18n();
  const { client } = useApi();
  const { user } = useAuth();
  const router = useRouter();
  const searchParams = useSearchParams();
  const paramsKey = searchParams.toString();

  const filter = useMemo(() => filterFrom(searchParams, user?.sites[0] ?? null), [paramsKey, user]);

  const [snapshot, setSnapshot] = useState<DashboardSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const asked = useRef(0);

  const load = useCallback(() => {
    if (!client || !filter) return;
    const mine = ++asked.current;
    client.dashboard.live(filter).then(
      (next) => {
        if (mine === asked.current) {
          setSnapshot(next);
          setError(null);
        }
      },
      (cause) => {
        if (mine === asked.current) setError(describeError(t, cause));
      },
    );
  }, [client, filter, t]);

  useEffect(() => {
    load();
  }, [load]);

  // The realtime channel signals a refresh only (FR-CFG-105); the actual, scoped tile data always comes back through `load`.
  useTopics(filter ? [`site:${filter.site_id}:dashboard`] : [], load);

  function updateFilter(key: (typeof FILTER_KEYS)[number], value: string) {
    const next = new URLSearchParams(searchParams.toString());
    if (value) next.set(key, value);
    else next.delete(key);
    router.replace(`?${next.toString()}`);
  }

  const duration = (seconds: number) => t("console.duration", { minutes: formatNumber(Math.floor(seconds / 60)), seconds: formatNumber(seconds % 60) });

  if (!filter) return <p className="qms-muted">{t("dashboard.needsSite")}</p>;

  return (
    <div className="qms-stack" data-testid="live-dashboard">
      <h1 className="qms-heading">{t("dashboard.title")}</h1>
      <FilterBar filter={filter} onChange={updateFilter} />
      {error && <ErrorAlert>{error}</ErrorAlert>}
      {!snapshot && !error && <p className="qms-muted">{t("dashboard.loading")}</p>}
      {snapshot && (
        <div className="qms-stack" data-testid="dashboard-tiles">
          <WaitingNowCard snapshot={snapshot} duration={duration} />
          <ServingNowCard snapshot={snapshot} duration={duration} />
          <CountersCard snapshot={snapshot} />
          <LongestWaitsCard snapshot={snapshot} duration={duration} client={client} onChanged={load} />
          <ThroughputCard snapshot={snapshot} />
          <AppointmentsCard snapshot={snapshot} />
          <RemoteQueueCard snapshot={snapshot} />
          <DeviceHealthCard snapshot={snapshot} />
          <ServedPerCounterCard snapshot={snapshot} client={client} onChanged={load} />
          <AgentStatusCard client={client} onChanged={load} />
          <StaffAlertCard filter={filter} client={client} />
          <AlertsCard filter={filter} client={client} />
        </div>
      )}
    </div>
  );
}

function FilterBar({ filter, onChange }: { filter: DashboardFilter; onChange: (key: (typeof FILTER_KEYS)[number], value: string) => void }) {
  const { t } = useI18n();
  return (
    <Card>
      <div className="qms-row" data-testid="dashboard-filter">
        <TextField id="dashboard-site" label={t("dashboard.filter.site")} value={filter.site_id} onChange={(e) => onChange("site_id", e.target.value)} />
        <TextField id="dashboard-zone" label={t("dashboard.filter.zone")} value={filter.zone_id ?? ""} onChange={(e) => onChange("zone_id", e.target.value)} />
        <TextField
          id="dashboard-group"
          label={t("dashboard.filter.serviceGroup")}
          value={filter.service_group_id ?? ""}
          onChange={(e) => onChange("service_group_id", e.target.value)}
        />
        <TextField id="dashboard-service" label={t("dashboard.filter.service")} value={filter.service_id ?? ""} onChange={(e) => onChange("service_id", e.target.value)} />
        <TextField
          id="dashboard-priority"
          label={t("dashboard.filter.priorityClass")}
          value={filter.priority_class_id ?? ""}
          onChange={(e) => onChange("priority_class_id", e.target.value)}
        />
      </div>
    </Card>
  );
}

function WaitingNowCard({ snapshot, duration }: { snapshot: DashboardSnapshot; duration: (s: number) => string }) {
  const { t, language, formatNumber } = useI18n();
  return (
    <Card>
      <h3 className="qms-label">{t("dashboard.tiles.waitingNow")}</h3>
      {snapshot.waiting_now.length === 0 && <p className="qms-muted">{t("dashboard.waitingNow.empty")}</p>}
      <ul className="qms-list">
        {snapshot.waiting_now.map((row) => (
          <li key={row.service_group_id}>
            {t("dashboard.waitingNow.row", { group: localisedName(row.service_group_name, language), count: formatNumber(row.count), duration: duration(row.longest_wait_seconds) })}
          </li>
        ))}
      </ul>
    </Card>
  );
}

function ServingNowCard({ snapshot, duration }: { snapshot: DashboardSnapshot; duration: (s: number) => string }) {
  const { t } = useI18n();
  return (
    <Card>
      <h3 className="qms-label">{t("dashboard.tiles.servingNow")}</h3>
      {snapshot.serving_now.length === 0 && <p className="qms-muted">{t("dashboard.servingNow.empty")}</p>}
      <ul className="qms-list">
        {snapshot.serving_now.map((row) => (
          <li key={row.ticket_id}>{t("dashboard.servingNow.row", { token: row.token_number, counter: row.counter_label, duration: duration(row.elapsed_seconds) })}</li>
        ))}
      </ul>
    </Card>
  );
}

function CountersCard({ snapshot }: { snapshot: DashboardSnapshot }) {
  const { t, formatNumber } = useI18n();
  const c = snapshot.counters;
  return (
    <Card>
      <h3 className="qms-label">{t("dashboard.tiles.counters")}</h3>
      <ul className="qms-list">
        <li>{t("dashboard.counters.open", { count: formatNumber(c.open) })}</li>
        <li>{t("dashboard.counters.onBreak", { count: formatNumber(c.on_break) })}</li>
        <li>{t("dashboard.counters.closed", { count: formatNumber(c.closed) })}</li>
        <li>{t("dashboard.counters.idle", { count: formatNumber(c.idle_with_queue) })}</li>
      </ul>
    </Card>
  );
}

interface ApiLike {
  tickets: { setPriority: (id: string, input: { priority_class_id: string; reason: string }) => Promise<unknown> };
  sessions: { forceClose: (id: string, reason?: string) => Promise<unknown> };
  breaks: { setAvailability: (agentId: string, input: { status: "available" | "on_break"; break_type_id?: string; reason?: string }) => Promise<unknown> };
}

function LongestWaitsCard({
  snapshot,
  duration,
  client,
  onChanged,
}: {
  snapshot: DashboardSnapshot;
  duration: (s: number) => string;
  client: ApiLike | null;
  onChanged: () => void;
}) {
  const { t, formatNumber } = useI18n();
  const [selected, setSelected] = useState("");
  const [classId, setClassId] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!client || !selected || !classId) return;
    try {
      await client.tickets.setPriority(selected, { priority_class_id: classId, reason });
      setError(null);
      setReason("");
      onChanged();
    } catch (cause) {
      setError(describeError(t, cause));
    }
  }

  return (
    <Card>
      <h3 className="qms-label">{t("dashboard.tiles.longestWaits")}</h3>
      {snapshot.longest_waits.length === 0 && <p className="qms-muted">{t("dashboard.longestWaits.empty")}</p>}
      <ul className="qms-list">
        {snapshot.longest_waits.map((row) => (
          <li key={row.ticket_id}>
            {t("dashboard.longestWaits.row", { token: row.token_number, count: formatNumber(row.wait_seconds), duration: duration(row.wait_seconds) })}
            {row.escalated && <span className="qms-badge qms-badge--down"> {t("dashboard.longestWaits.escalated")}</span>}
            {row.sla_breached && <span className="qms-badge qms-badge--down"> {t("dashboard.longestWaits.slaBreached")}</span>}
          </li>
        ))}
      </ul>
      {snapshot.longest_waits.length > 0 && (
        <div className="qms-row">
          <label className="qms-label" htmlFor="reprioritise-ticket">
            {t("dashboard.actions.reprioritise")}
          </label>
          <select id="reprioritise-ticket" className="qms-input" value={selected} onChange={(e) => setSelected(e.target.value)}>
            <option value="">{t("dashboard.filter.all")}</option>
            {snapshot.longest_waits.map((row) => (
              <option key={row.ticket_id} value={row.ticket_id}>
                {row.token_number}
              </option>
            ))}
          </select>
          <TextField id="reprioritise-class" label={t("dashboard.filter.priorityClass")} value={classId} onChange={(e) => setClassId(e.target.value)} />
          <TextField id="reprioritise-reason" label={t("dashboard.actions.reprioritise.reason")} value={reason} onChange={(e) => setReason(e.target.value)} />
          <Button type="button" onClick={submit} disabled={!selected || !classId}>
            {t("dashboard.actions.reprioritise.submit")}
          </Button>
        </div>
      )}
      {error && <ErrorAlert>{error}</ErrorAlert>}
    </Card>
  );
}

function ThroughputCard({ snapshot }: { snapshot: DashboardSnapshot }) {
  const { t, formatNumber } = useI18n();
  const p = snapshot.throughput_today;
  return (
    <Card>
      <h3 className="qms-label">{t("dashboard.tiles.throughputToday")}</h3>
      <ul className="qms-list">
        <li>{t("dashboard.throughput.served", { count: formatNumber(p.served) })}</li>
        <li>{t("dashboard.throughput.cancelled", { count: formatNumber(p.cancelled) })}</li>
        <li>{t("dashboard.throughput.noShow", { count: formatNumber(p.no_show) })}</li>
        <li>{t("dashboard.throughput.transferred", { count: formatNumber(p.transferred) })}</li>
      </ul>
    </Card>
  );
}

function AppointmentsCard({ snapshot }: { snapshot: DashboardSnapshot }) {
  const { t, formatNumber } = useI18n();
  const a = snapshot.appointments_today;
  return (
    <Card>
      <h3 className="qms-label">{t("dashboard.tiles.appointmentsToday")}</h3>
      <ul className="qms-list">
        <li>{t("dashboard.appointments.booked", { count: formatNumber(a.booked) })}</li>
        <li>{t("dashboard.appointments.checkedIn", { count: formatNumber(a.checked_in) })}</li>
        <li>{t("dashboard.appointments.noShow", { count: formatNumber(a.no_show) })}</li>
        <li>{t("dashboard.appointments.upcoming", { count: formatNumber(a.upcoming_next_hour) })}</li>
      </ul>
    </Card>
  );
}

function RemoteQueueCard({ snapshot }: { snapshot: DashboardSnapshot }) {
  const { t, formatNumber } = useI18n();
  const r = snapshot.remote_queue;
  return (
    <Card>
      <h3 className="qms-label">{t("dashboard.tiles.remoteQueue")}</h3>
      <ul className="qms-list">
        <li>{t("dashboard.remoteQueue.remote", { count: formatNumber(r.remote) })}</li>
        <li>{t("dashboard.remoteQueue.approaching", { count: formatNumber(r.approaching) })}</li>
        <li>{t("dashboard.remoteQueue.present", { count: formatNumber(r.present) })}</li>
        <li>{t("dashboard.remoteQueue.forfeited", { count: formatNumber(r.forfeited) })}</li>
      </ul>
    </Card>
  );
}

function DeviceHealthCard({ snapshot }: { snapshot: DashboardSnapshot }) {
  const { t, formatNumber } = useI18n();
  const d = snapshot.device_health;
  return (
    <Card>
      <h3 className="qms-label">{t("dashboard.tiles.deviceHealth")}</h3>
      <ul className="qms-list">
        <li>{t("dashboard.deviceHealth.kiosksOffline", { count: formatNumber(d.kiosks_offline) })}</li>
        <li>{t("dashboard.deviceHealth.displaysOffline", { count: formatNumber(d.displays_offline) })}</li>
        <li>{d.printers_offline === null ? t("dashboard.deviceHealth.printersOffline") : formatNumber(d.printers_offline)}</li>
      </ul>
    </Card>
  );
}

function ServedPerCounterCard({ snapshot, client, onChanged }: { snapshot: DashboardSnapshot; client: ApiLike | null; onChanged: () => void }) {
  const { t, formatNumber } = useI18n();
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function forceClose(sessionId: string) {
    if (!client) return;
    setBusyId(sessionId);
    try {
      await client.sessions.forceClose(sessionId);
      setError(null);
      onChanged();
    } catch (cause) {
      setError(describeError(t, cause));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <Card>
      <h3 className="qms-label">{t("dashboard.tiles.servedPerCounter")}</h3>
      <ul className="qms-list">
        {snapshot.served_per_open_counter.map((row) => (
          <li key={row.counter_id}>
            {t("dashboard.servedPerCounter.row", { counter: row.label, count: formatNumber(row.served_count) })}
            {row.session_id && (
              <Button type="button" variant="secondary" disabled={busyId === row.session_id} onClick={() => forceClose(row.session_id as string)}>
                {t("dashboard.actions.forceClose")}
              </Button>
            )}
          </li>
        ))}
      </ul>
      {error && <ErrorAlert>{error}</ErrorAlert>}
    </Card>
  );
}

function AgentStatusCard({ client, onChanged }: { client: ApiLike | null; onChanged: () => void }) {
  const { t } = useI18n();
  const [agentId, setAgentId] = useState("");
  const [status, setStatus] = useState<"available" | "on_break">("available");
  const [breakTypeId, setBreakTypeId] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!client || !agentId) return;
    try {
      await client.breaks.setAvailability(agentId, { status, break_type_id: status === "on_break" ? breakTypeId : undefined, reason: reason || undefined });
      setError(null);
      setReason("");
      onChanged();
    } catch (cause) {
      setError(describeError(t, cause));
    }
  }

  return (
    <Card>
      <h3 className="qms-label">{t("dashboard.actions.setAvailability")}</h3>
      <div className="qms-row">
        <TextField id="availability-agent" label="Agent" value={agentId} onChange={(e) => setAgentId(e.target.value)} />
        <select className="qms-input" value={status} onChange={(e) => setStatus(e.target.value as "available" | "on_break")} aria-label={t("dashboard.actions.setAvailability")}>
          <option value="available">available</option>
          <option value="on_break">on_break</option>
        </select>
        {status === "on_break" && <TextField id="availability-break-type" label="Break type" value={breakTypeId} onChange={(e) => setBreakTypeId(e.target.value)} />}
        <TextField id="availability-reason" label={t("dashboard.actions.reprioritise.reason")} value={reason} onChange={(e) => setReason(e.target.value)} />
        <Button type="button" onClick={submit} disabled={!agentId}>
          {t("dashboard.actions.setAvailability")}
        </Button>
      </div>
      {error && <ErrorAlert>{error}</ErrorAlert>}
    </Card>
  );
}

function StaffAlertCard({ filter, client }: { filter: DashboardFilter; client: { dashboard: { sendStaffAlert: (siteId: string, message: string) => Promise<void> } } | null }) {
  const { t } = useI18n();
  const [message, setMessage] = useState("");
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function send() {
    if (!client || !message.trim()) return;
    try {
      await client.dashboard.sendStaffAlert(filter.site_id, message.trim());
      setSent(true);
      setMessage("");
      setError(null);
    } catch (cause) {
      setSent(false);
      setError(describeError(t, cause));
    }
  }

  return (
    <Card>
      <h3 className="qms-label">{t("dashboard.actions.sendAlert")}</h3>
      <div className="qms-row">
        <TextField id="staff-alert-message" label={t("dashboard.actions.sendAlert.placeholder")} value={message} onChange={(e) => setMessage(e.target.value)} />
        <Button type="button" onClick={send} disabled={!message.trim()}>
          {t("dashboard.actions.sendAlert.send")}
        </Button>
      </div>
      {sent && <p className="qms-muted">{t("dashboard.actions.sendAlert.sent")}</p>}
      {error && <ErrorAlert>{error}</ErrorAlert>}
    </Card>
  );
}

interface AlertsApiLike {
  alerts: {
    list: (siteId: string, state?: "open" | "acknowledged") => Promise<{ items: Alert[] }>;
    acknowledge: (id: string, note?: string) => Promise<Alert>;
  };
}

/**
 * Threshold alerts (SRS §15.4, ticket 47): the Site's current open alerts, refreshed through `site:{id}:alerts`
 * (`alert.raised` / `alert.acknowledged`, FR-MON-021) the same way the rest of this dashboard refreshes through its
 * own topic — a signal only, so every one re-asks the caller's own scoped `GET /sites/{id}/alerts` rather than trust
 * a broadcast payload (FR-CFG-105). Acknowledging with an optional note is FR-MON-022.
 */
function AlertsCard({ filter, client }: { filter: DashboardFilter; client: AlertsApiLike | null }) {
  const { t, formatNumber } = useI18n();
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    if (!client) return;
    client.alerts.list(filter.site_id, "open").then(
      (result) => {
        setAlerts(result.items);
        setError(null);
      },
      (cause) => setError(describeError(t, cause)),
    );
  }, [client, filter.site_id, t]);

  useEffect(() => {
    load();
  }, [load]);

  useTopics(filter ? [`site:${filter.site_id}:alerts`] : [], load);

  async function acknowledge(id: string) {
    if (!client) return;
    setBusyId(id);
    try {
      await client.alerts.acknowledge(id, notes[id]?.trim() || undefined);
      setError(null);
      load();
    } catch (cause) {
      setError(describeError(t, cause));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <Card>
      <h3 className="qms-label">{t("dashboard.tiles.alerts")}</h3>
      {alerts.length === 0 && <p className="qms-muted">{t("dashboard.alerts.empty")}</p>}
      <ul className="qms-list">
        {alerts.map((a) => (
          <li key={a.id} className="qms-row">
            <span>
              {t(`dashboard.alerts.type.${a.threshold_type}`)}:{" "}
              {t("dashboard.alerts.row", { measured: formatNumber(a.measured_value), threshold: formatNumber(a.threshold_value), count: formatNumber(a.breach_count) })}
              {a.escalated_at && <span className="qms-badge qms-badge--down"> {t("dashboard.alerts.escalated")}</span>}
            </span>
            <TextField
              id={`alert-note-${a.id}`}
              label={t("dashboard.alerts.note")}
              value={notes[a.id] ?? ""}
              onChange={(e) => setNotes((n) => ({ ...n, [a.id]: e.target.value }))}
            />
            <Button type="button" disabled={busyId === a.id} onClick={() => acknowledge(a.id)}>
              {t("dashboard.alerts.acknowledge")}
            </Button>
          </li>
        ))}
      </ul>
      {error && <ErrorAlert>{error}</ErrorAlert>}
    </Card>
  );
}
