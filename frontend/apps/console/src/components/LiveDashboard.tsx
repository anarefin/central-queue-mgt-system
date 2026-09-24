"use client";

import type { Alert, DashboardFilter, DashboardSnapshot, PriorityClass, ServiceGroup, Zone } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Badge, Button, Card, EmptyState, ErrorAlert, FIELD_INPUT_CLASSES, Picker, Skeleton, TextField } from "@qms/ui";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useAuth } from "../lib/auth";
import { describeError, localisedName, useOnDemandList } from "../lib/console-support";
import { canViewDashboard } from "../lib/permissions";
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
 * permission-checked — and send a staff alert.
 *
 * Client-side gate (ticket 64): a principal whose roles carry neither `dashboard:view_all` nor
 * `dashboard:view_own_groups` (SRS §5.2) is told so instead of seeing a broken page. This is a convenience only —
 * the API's own 403 is the real control, and one from `GET /dashboard/live` shows the identical state.
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
  const [forbidden, setForbidden] = useState(false);
  const asked = useRef(0);

  const load = useCallback(() => {
    if (!client || !filter) return;
    const mine = ++asked.current;
    client.dashboard.live(filter).then(
      (next) => {
        if (mine === asked.current) {
          setSnapshot(next);
          setError(null);
          setForbidden(false);
        }
      },
      (cause) => {
        if (mine !== asked.current) return;
        if (cause instanceof Object && "code" in cause && (cause as { code: unknown }).code === "forbidden") {
          setForbidden(true);
        } else {
          setError(describeError(t, cause));
        }
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

  // While the signed-in principal is still being read, neither grant nor refuse yet — refusing too early would
  // flash the "not permitted" screen at everyone on every load.
  if (user === null) return <p className="text-fg-muted">{t("common.loading")}</p>;
  if (!canViewDashboard(user.roles) || forbidden) return <NotPermittedState />;

  if (!filter) return <p className="text-fg-muted">{t("dashboard.needsSite")}</p>;

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-4 lg:flex-row lg:items-start" data-testid="live-dashboard">
      <FilterSidebar filter={filter} onChange={updateFilter} />
      <div className="flex min-w-0 flex-1 flex-col gap-4">
        <h1 className="text-2xl font-semibold text-fg">{t("dashboard.title")}</h1>
        {error && <ErrorAlert>{error}</ErrorAlert>}
        {!snapshot && !error && <TileSkeletons label={t("dashboard.loading")} />}
        {snapshot && (
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3" data-testid="dashboard-tiles">
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
    </div>
  );
}

function NotPermittedState() {
  const { t } = useI18n();
  return (
    <EmptyState
      title={t("dashboard.notPermitted.title")}
      body={t("dashboard.notPermitted.body")}
      action={
        <Link href="/" className="font-medium text-primary underline underline-offset-2 hover:no-underline">
          {t("dashboard.notPermitted.backToCounter")}
        </Link>
      }
    />
  );
}

function TileSkeletons({ label }: { label: string }) {
  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3" aria-label={label} role="status">
      {Array.from({ length: 6 }, (_, i) => (
        <Card key={i}>
          <Skeleton className="h-4 w-1/2" />
          <div className="mt-3 flex flex-col gap-2">
            <Skeleton className="h-3 w-full" />
            <Skeleton className="h-3 w-2/3" />
          </div>
        </Card>
      ))}
    </div>
  );
}

/**
 * The dashboard's own filters, as pickers loaded on demand rather than raw ids typed into text fields (ticket 64):
 * Site (the ones this signed-in principal belongs to), Zone and Service group of the chosen Site, Service of the
 * chosen group, and Priority class. The URL query still carries every choice (FR-MON-002), so a shared link
 * restores the same view — {@link LiveDashboard} is what reads it back.
 */
function FilterSidebar({ filter, onChange }: { filter: DashboardFilter; onChange: (key: (typeof FILTER_KEYS)[number], value: string) => void }) {
  const { t, language } = useI18n();
  const { client } = useApi();
  const { user } = useAuth();

  const sites = useOnDemandList(() => client!.sites.list(), [client]);
  const zones = useOnDemandList(() => client!.sites.zones(filter.site_id), [client, filter.site_id]);
  const groups = useOnDemandList(() => client!.catalogue.groups(filter.site_id), [client, filter.site_id]);
  const services = useOnDemandList(() => client!.catalogue.services(filter.service_group_id ?? ""), [client, filter.service_group_id]);
  const classes = useOnDemandList(() => client!.priority.classes(), [client]);

  const mySiteIds = new Set(user?.sites ?? []);
  const siteOptions = (sites.items ?? []).filter((site) => mySiteIds.has(site.id)).map((site) => ({ value: site.id, label: site.name }));
  const zoneOptions = (zones.items ?? []).map((zone: Zone) => ({ value: zone.id, label: zone.name }));
  const groupOptions = (groups.items ?? []).map((group: ServiceGroup) => ({ value: group.id, label: localisedName(group.name_i18n, language) }));
  const serviceOptions = (services.items ?? []).map((service) => ({ value: service.id, label: localisedName(service.name_i18n, language) }));
  const classOptions = (classes.items ?? []).map((cls: PriorityClass) => ({ value: cls.id, label: localisedName(cls.name_i18n, language) }));

  return (
    <aside className="w-full lg:w-72 lg:shrink-0" data-testid="dashboard-filter">
      <Card>
        <h2 className="text-sm font-semibold text-fg">{t("dashboard.filter.title")}</h2>
        <div className="flex flex-col gap-3">
        <Picker
          id="dashboard-site"
          label={t("dashboard.filter.site")}
          value={filter.site_id}
          onChange={(value) => onChange("site_id", value)}
          options={siteOptions}
          loading={sites.loading}
          onOpen={sites.request}
          noMatchesLabel={t("dashboard.filter.noMatches")}
        />
        <Picker
          id="dashboard-zone"
          label={t("dashboard.filter.zone")}
          value={filter.zone_id ?? ""}
          onChange={(value) => onChange("zone_id", value)}
          options={zoneOptions}
          placeholder={t("dashboard.filter.all")}
          loading={zones.loading}
          onOpen={zones.request}
          noMatchesLabel={t("dashboard.filter.noMatches")}
        />
        <Picker
          id="dashboard-group"
          label={t("dashboard.filter.serviceGroup")}
          value={filter.service_group_id ?? ""}
          onChange={(value) => onChange("service_group_id", value)}
          options={groupOptions}
          placeholder={t("dashboard.filter.all")}
          onOpen={groups.request}
          noMatchesLabel={t("dashboard.filter.noMatches")}
        />
        <Picker
          id="dashboard-service"
          label={t("dashboard.filter.service")}
          value={filter.service_id ?? ""}
          onChange={(value) => onChange("service_id", value)}
          options={serviceOptions}
          placeholder={t("dashboard.filter.all")}
          disabled={!filter.service_group_id}
          helpText={filter.service_group_id ? undefined : t("dashboard.filter.needsGroupFirst")}
          onOpen={filter.service_group_id ? services.request : undefined}
          noMatchesLabel={t("dashboard.filter.noMatches")}
        />
        <Picker
          id="dashboard-priority"
          label={t("dashboard.filter.priorityClass")}
          value={filter.priority_class_id ?? ""}
          onChange={(value) => onChange("priority_class_id", value)}
          options={classOptions}
          placeholder={t("dashboard.filter.all")}
          onOpen={classes.request}
          noMatchesLabel={t("dashboard.filter.noMatches")}
        />
        </div>
      </Card>
    </aside>
  );
}

function WaitingNowCard({ snapshot, duration }: { snapshot: DashboardSnapshot; duration: (s: number) => string }) {
  const { t, language, formatNumber } = useI18n();
  return (
    <Card>
      <h3 className="text-sm font-semibold text-fg">{t("dashboard.tiles.waitingNow")}</h3>
      {snapshot.waiting_now.length === 0 && <p className="text-sm text-fg-muted">{t("dashboard.waitingNow.empty")}</p>}
      <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm text-fg">
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
      <h3 className="text-sm font-semibold text-fg">{t("dashboard.tiles.servingNow")}</h3>
      {snapshot.serving_now.length === 0 && <p className="text-sm text-fg-muted">{t("dashboard.servingNow.empty")}</p>}
      <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm text-fg">
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
      <h3 className="text-sm font-semibold text-fg">{t("dashboard.tiles.counters")}</h3>
      <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm text-fg">
        <li>{t("dashboard.counters.open", { count: formatNumber(c.open) })}</li>
        <li>{t("dashboard.counters.onBreak", { count: formatNumber(c.on_break) })}</li>
        <li>{t("dashboard.counters.closed", { count: formatNumber(c.closed) })}</li>
        <li>{t("dashboard.counters.idle", { count: formatNumber(c.idle_with_queue) })}</li>
      </ul>
    </Card>
  );
}

interface ActionApiLike {
  tickets: { setPriority: (id: string, input: { priority_class_id: string; reason: string }) => Promise<unknown> };
  sessions: { forceClose: (id: string, reason?: string) => Promise<unknown> };
  breaks: {
    setAvailability: (agentId: string, input: { status: "available" | "on_break"; break_type_id?: string; reason?: string }) => Promise<unknown>;
    availability: () => Promise<{ items: { agent_id: string; agent_name: string | null }[] }>;
    types: () => Promise<{ items: { id: string; name_i18n: Record<string, string>; active: boolean }[] }>;
  };
  priority: { classes: () => Promise<{ items: PriorityClass[] }> };
}

function LongestWaitsCard({
  snapshot,
  duration,
  client,
  onChanged,
}: {
  snapshot: DashboardSnapshot;
  duration: (s: number) => string;
  client: ActionApiLike | null;
  onChanged: () => void;
}) {
  const { t, language, formatNumber } = useI18n();
  const [selected, setSelected] = useState("");
  const [classId, setClassId] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const classes = useOnDemandList(() => client!.priority.classes(), [client]);
  const classOptions = (classes.items ?? []).map((cls) => ({ value: cls.id, label: localisedName(cls.name_i18n, language) }));

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
      <h3 className="text-sm font-semibold text-fg">{t("dashboard.tiles.longestWaits")}</h3>
      {snapshot.longest_waits.length === 0 && <p className="text-sm text-fg-muted">{t("dashboard.longestWaits.empty")}</p>}
      <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm text-fg">
        {snapshot.longest_waits.map((row) => (
          <li key={row.ticket_id} className="flex flex-wrap items-center gap-2">
            {t("dashboard.longestWaits.row", { token: row.token_number, count: formatNumber(row.wait_seconds), duration: duration(row.wait_seconds) })}
            {row.escalated && <Badge variant="danger">{t("dashboard.longestWaits.escalated")}</Badge>}
            {row.sla_breached && <Badge variant="danger">{t("dashboard.longestWaits.slaBreached")}</Badge>}
          </li>
        ))}
      </ul>
      {snapshot.longest_waits.length > 0 && (
        <div className="flex flex-col gap-2">
          <label className="text-sm font-medium text-fg" htmlFor="reprioritise-ticket">
            {t("dashboard.actions.reprioritise")}
          </label>
          <select id="reprioritise-ticket" className={FIELD_INPUT_CLASSES} value={selected} onChange={(e) => setSelected(e.target.value)}>
            <option value="">{t("dashboard.filter.all")}</option>
            {snapshot.longest_waits.map((row) => (
              <option key={row.ticket_id} value={row.ticket_id}>
                {row.token_number}
              </option>
            ))}
          </select>
          <Picker
            id="reprioritise-class"
            label={t("dashboard.filter.priorityClass")}
            value={classId}
            onChange={setClassId}
            options={classOptions}
            onOpen={classes.request}
            noMatchesLabel={t("dashboard.filter.noMatches")}
          />
          <TextField id="reprioritise-reason" label={t("dashboard.actions.reprioritise.reason")} value={reason} onChange={(e) => setReason(e.target.value)} />
          <div>
            <Button type="button" onClick={submit} disabled={!selected || !classId}>
              {t("dashboard.actions.reprioritise.submit")}
            </Button>
          </div>
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
      <h3 className="text-sm font-semibold text-fg">{t("dashboard.tiles.throughputToday")}</h3>
      <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm text-fg">
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
      <h3 className="text-sm font-semibold text-fg">{t("dashboard.tiles.appointmentsToday")}</h3>
      <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm text-fg">
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
      <h3 className="text-sm font-semibold text-fg">{t("dashboard.tiles.remoteQueue")}</h3>
      <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm text-fg">
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
      <h3 className="text-sm font-semibold text-fg">{t("dashboard.tiles.deviceHealth")}</h3>
      <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm text-fg">
        <li>{t("dashboard.deviceHealth.kiosksOffline", { count: formatNumber(d.kiosks_offline) })}</li>
        <li>{t("dashboard.deviceHealth.displaysOffline", { count: formatNumber(d.displays_offline) })}</li>
        <li>{d.printers_offline === null ? t("dashboard.deviceHealth.printersOffline") : formatNumber(d.printers_offline)}</li>
      </ul>
    </Card>
  );
}

function ServedPerCounterCard({ snapshot, client, onChanged }: { snapshot: DashboardSnapshot; client: ActionApiLike | null; onChanged: () => void }) {
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
      <h3 className="text-sm font-semibold text-fg">{t("dashboard.tiles.servedPerCounter")}</h3>
      <ul className="m-0 flex list-none flex-col gap-2 p-0 text-sm text-fg">
        {snapshot.served_per_open_counter.map((row) => (
          <li key={row.counter_id} className="flex flex-wrap items-center justify-between gap-2">
            {t("dashboard.servedPerCounter.row", { counter: row.label, count: formatNumber(row.served_count) })}
            {row.session_id && (
              <Button type="button" variant="secondary" size="sm" disabled={busyId === row.session_id} onClick={() => forceClose(row.session_id as string)}>
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

function AgentStatusCard({ client, onChanged }: { client: ActionApiLike | null; onChanged: () => void }) {
  const { t } = useI18n();
  const [agentId, setAgentId] = useState("");
  const [status, setStatus] = useState<"available" | "on_break">("available");
  const [breakTypeId, setBreakTypeId] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const agents = useOnDemandList(() => client!.breaks.availability(), [client]);
  const breakTypes = useOnDemandList(() => client!.breaks.types(), [client]);
  const agentOptions = (agents.items ?? []).map((a) => ({ value: a.agent_id, label: a.agent_name ?? a.agent_id }));
  const breakTypeOptions = (breakTypes.items ?? []).filter((bt) => bt.active).map((bt) => ({ value: bt.id, label: bt.name_i18n.en ?? Object.values(bt.name_i18n)[0] ?? bt.id }));

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
      <h3 className="text-sm font-semibold text-fg">{t("dashboard.actions.setAvailability")}</h3>
      <div className="flex flex-col gap-2">
        <Picker id="availability-agent" label={t("dashboard.filter.agent")} value={agentId} onChange={setAgentId} options={agentOptions} onOpen={agents.request} noMatchesLabel={t("dashboard.filter.noMatches")} />
        <select
          className={FIELD_INPUT_CLASSES}
          value={status}
          onChange={(e) => setStatus(e.target.value as "available" | "on_break")}
          aria-label={t("dashboard.actions.setAvailability")}
        >
          <option value="available">available</option>
          <option value="on_break">on_break</option>
        </select>
        {status === "on_break" && (
          <Picker
            id="availability-break-type"
            label={t("console.break.type")}
            value={breakTypeId}
            onChange={setBreakTypeId}
            options={breakTypeOptions}
            onOpen={breakTypes.request}
            noMatchesLabel={t("dashboard.filter.noMatches")}
          />
        )}
        <TextField id="availability-reason" label={t("dashboard.actions.reprioritise.reason")} value={reason} onChange={(e) => setReason(e.target.value)} />
        <div>
          <Button type="button" onClick={submit} disabled={!agentId}>
            {t("dashboard.actions.setAvailability")}
          </Button>
        </div>
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
      <h3 className="text-sm font-semibold text-fg">{t("dashboard.actions.sendAlert")}</h3>
      <div className="flex flex-col gap-2">
        <TextField id="staff-alert-message" label={t("dashboard.actions.sendAlert.placeholder")} value={message} onChange={(e) => setMessage(e.target.value)} />
        <div>
          <Button type="button" onClick={send} disabled={!message.trim()}>
            {t("dashboard.actions.sendAlert.send")}
          </Button>
        </div>
      </div>
      {sent && <p className="text-sm text-fg-muted">{t("dashboard.actions.sendAlert.sent")}</p>}
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
      <h3 className="text-sm font-semibold text-fg">{t("dashboard.tiles.alerts")}</h3>
      {alerts.length === 0 && <p className="text-sm text-fg-muted">{t("dashboard.alerts.empty")}</p>}
      <ul className="m-0 flex list-none flex-col gap-2 p-0 text-sm text-fg">
        {alerts.map((a) => (
          <li key={a.id} className="flex flex-col gap-2">
            <span>
              {t(`dashboard.alerts.type.${a.threshold_type}`)}:{" "}
              {t("dashboard.alerts.row", { measured: formatNumber(a.measured_value), threshold: formatNumber(a.threshold_value), count: formatNumber(a.breach_count) })}
              {a.escalated_at && <Badge variant="danger">{t("dashboard.alerts.escalated")}</Badge>}
            </span>
            <TextField
              id={`alert-note-${a.id}`}
              label={t("dashboard.alerts.note")}
              value={notes[a.id] ?? ""}
              onChange={(e) => setNotes((n) => ({ ...n, [a.id]: e.target.value }))}
            />
            <div>
              <Button type="button" size="sm" disabled={busyId === a.id} onClick={() => acknowledge(a.id)}>
                {t("dashboard.alerts.acknowledge")}
              </Button>
            </div>
          </li>
        ))}
      </ul>
      {error && <ErrorAlert>{error}</ErrorAlert>}
    </Card>
  );
}
