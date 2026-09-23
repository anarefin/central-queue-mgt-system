"use client";

import {
  CHANNELS,
  OPERATIONAL_REPORT_KEYS,
  type Channel,
  type OperationalReportKey,
  type OperationalReportResponse,
  type OperationalReportRow,
  type OperationalReportValue,
  type PriorityClass,
  type ReportGrain,
  type ServiceEntry,
  type ServiceGroup,
  type Site,
  type UserSummary,
  type Zone,
} from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, DataTable, EmptyState, ErrorAlert, reportRange, SelectField, TextField, type DataTableColumn } from "@qms/ui";
import { useState } from "react";
import { localisedName, useFormValidation, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

const GRAINS: ReportGrain[] = ["hour", "day", "month", "year"];

/** Which field carries this report's own row label, whether that field is a per-language name, and the ordered
 * metric columns after it (§16.1's own catalogue). A department/site row rolls the service report up one or two
 * levels (§16.1), so it shares the service report's own metric set. */
const REPORT_META: Record<OperationalReportKey, { nameField: string; nameLocalised: boolean; metrics: string[] }> = {
  "visitor-flow": {
    nameField: "bucket",
    nameLocalised: false,
    metrics: ["issued", "served", "cancelled", "no_show", "peak_concurrent_waiting", "abandonment_rate_pct"],
  },
  counter: {
    nameField: "counter_label",
    nameLocalised: false,
    metrics: ["zone_name", "site_name", "sessions", "open_seconds", "served", "serving_seconds", "break_seconds", "idle_seconds", "utilisation_pct"],
  },
  agent: {
    nameField: "agent_name",
    nameLocalised: false,
    metrics: [
      "services_served",
      "services_cancelled",
      "avg_wait_seconds",
      "avg_service_seconds",
      "total_service_seconds",
      "avg_break_seconds",
      "login_adherence_pct",
      "successful_token_rate_pct",
    ],
  },
  service: { nameField: "service_name", nameLocalised: true, metrics: ["volume", "avg_wait_seconds", "p90_wait_seconds", "avg_handling_seconds", "sla_attainment_pct"] },
  department: {
    nameField: "service_group_name",
    nameLocalised: true,
    metrics: ["volume", "avg_wait_seconds", "p90_wait_seconds", "avg_handling_seconds", "sla_attainment_pct"],
  },
  site: { nameField: "site_name", nameLocalised: false, metrics: ["volume", "avg_wait_seconds", "p90_wait_seconds", "avg_handling_seconds", "sla_attainment_pct"] },
};

/**
 * The six operational reports (SRS §16.1, ticket 50): visitor flow, counter, agent, service, department and site,
 * each grouped for the requested period and compared against the previous equivalent period, absolute and percent
 * (FR-RPT-010, FR-MON-011). Percentiles (P90 wait) come straight from raw Ticket records, never from an already
 * grouped average (FR-MON-010) — this card only renders what the server already computed that way.
 */
export function OperationalReportsCard({ site }: { site: Site }) {
  const { t, language, formatDate, formatTime } = useI18n();
  const { client } = useApi();

  const zones = useList<Zone>(client ? () => client.sites.zones(site.id) : null, [client, site.id]);
  const groups = useList<ServiceGroup>(client ? () => client.catalogue.groups(site.id) : null, [client, site.id]);
  const priorityClasses = useList<PriorityClass>(client ? () => client.priority.classes() : null, [client]);
  const users = useList<UserSummary>(client ? () => client.users.list() : null, [client]);

  const [key, setKey] = useState<OperationalReportKey>("visitor-flow");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [grain, setGrain] = useState<ReportGrain>("day");
  const [zoneId, setZoneId] = useState("");
  const [groupId, setGroupId] = useState("");
  const [serviceId, setServiceId] = useState("");
  const [agentId, setAgentId] = useState("");
  const [priorityClassId, setPriorityClassId] = useState("");
  const [channel, setChannel] = useState<"" | Channel>("");
  const [visitorCategory, setVisitorCategory] = useState("");

  const services = useList<ServiceEntry>(client && groupId ? () => client.catalogue.services(groupId) : null, [client, groupId]);

  const [result, setResult] = useState<OperationalReportResponse | null>(null);
  const { busy, error, run } = useSubmit();

  const rangeChosen = from !== "" && to !== "";
  const rangeValidation = useFormValidation<{ from: string; to: string }>(
    { range: (v) => reportRange(v.from, v.to) },
    { range: "operational-report-to" },
  );
  const validateRange = () => rangeValidation.validateField("range", { from, to });

  async function runReport() {
    if (!rangeValidation.validateAll({ from, to })) return;
    await run(async () => {
      const response = await client!.reports.runOperational(key, {
        site_id: site.id,
        from: new Date(from).toISOString(),
        to: new Date(to).toISOString(),
        zone_id: zoneId || undefined,
        service_group_id: groupId || undefined,
        service_id: serviceId || undefined,
        agent_id: agentId || undefined,
        priority_class_id: priorityClassId || undefined,
        channel: channel || undefined,
        visitor_category: visitorCategory || undefined,
        grain: key === "visitor-flow" ? grain : undefined,
      });
      setResult(response);
    });
  }

  const meta = REPORT_META[key];
  const nameOf = (names: Record<string, string>) => localisedName(names, language, site.default_language);

  function label(field: string): string {
    return t(`reports.metric.${field}`);
  }

  function formatValue(field: string, value: OperationalReportValue): string {
    if (value === null || value === undefined) return "—";
    if (field === meta.nameField) return formatName(field, value);
    if (field.endsWith("_seconds")) return Math.round(Number(value) / 60).toString();
    if (field.endsWith("_pct")) return `${Number(value).toFixed(1)}%`;
    if (typeof value === "object") return nameOf(value as Record<string, string>);
    return String(value);
  }

  function formatName(field: string, value: OperationalReportValue): string {
    if (field === "bucket" && typeof value === "string") return `${formatDate(new Date(value))} ${formatTime(new Date(value))}`;
    if (meta.nameLocalised && value && typeof value === "object") return nameOf(value as Record<string, string>);
    return value === null || value === undefined ? "—" : String(value);
  }

  function changeCell(field: string) {
    const change = result?.change[field];
    if (!change) return "—";
    const sign = change.absolute > 0 ? "+" : "";
    const percent = change.percent === null ? "" : ` (${change.percent > 0 ? "+" : ""}${change.percent.toFixed(1)}%)`;
    const absolute = field.endsWith("_seconds") ? Math.round(change.absolute / 60) : change.absolute;
    return `${sign}${absolute}${percent}`;
  }

  const waitByHourBand = (result?.extra?.["wait_by_hour_band"] as OperationalReportRow[] | undefined) ?? [];
  const channelMix = (result?.extra?.["channel_mix"] as OperationalReportRow[] | undefined) ?? [];

  // No column here is sortable: none of these four report shapes has a server-side sort to wire a `DataTable`
  // indicator to (unlike the detailed token report's `ReportSort`) — `DataTable` is used for its shared chrome
  // (borders, skeleton state, empty slot), not for sorting it does not yet support.
  const rowsColumns: DataTableColumn<{ row: OperationalReportRow; i: number }>[] = [
    { key: meta.nameField, header: label(meta.nameField), rowHeader: true, render: ({ row }) => formatValue(meta.nameField, row[meta.nameField] ?? null) },
    ...meta.metrics.map((m) => ({ key: m, header: label(m), render: ({ row }: { row: OperationalReportRow; i: number }) => formatValue(m, row[m] ?? null) })),
  ];

  const totalsMetrics = meta.metrics.filter((m) => m in (result?.totals ?? {}));
  type TotalsRow = { label: string; get: (m: string) => string };
  const totalsRows: TotalsRow[] = result
    ? [
        { label: t("reports.operational.totals"), get: (m) => formatValue(m, result.totals[m] ?? null) },
        { label: t("reports.operational.previousTotals"), get: (m) => formatValue(m, result.previous_totals[m] ?? null) },
        { label: t("reports.operational.change"), get: (m) => changeCell(m) },
      ]
    : [];
  const totalsColumns: DataTableColumn<TotalsRow>[] = [
    { key: "__label", header: "", rowHeader: true, render: (row) => row.label },
    ...totalsMetrics.map((m) => ({ key: m, header: label(m), render: (row: TotalsRow) => row.get(m) })),
  ];

  const waitByHourBandColumns: DataTableColumn<OperationalReportRow>[] = [
    { key: "service_id", header: t("reports.metric.service_name"), render: (row) => String(row["service_id"] ?? "—") },
    { key: "hour_band", header: t("reports.operational.hourBand"), render: (row) => String(row["hour_band"] ?? "—") },
    { key: "avg_wait_seconds", header: t("reports.metric.avg_wait_seconds"), render: (row) => formatValue("avg_wait_seconds", (row["avg_wait_seconds"] as OperationalReportValue) ?? null) },
    { key: "p90_wait_seconds", header: t("reports.metric.p90_wait_seconds"), render: (row) => formatValue("p90_wait_seconds", (row["p90_wait_seconds"] as OperationalReportValue) ?? null) },
  ];

  const channelMixColumns: DataTableColumn<OperationalReportRow>[] = [
    { key: "channel", header: t("reports.filters.channel"), render: (row) => t(`catalogue.channel.${String(row["channel"])}`) },
    { key: "count", header: t("reports.metric.issued"), render: (row) => String(row["count"] ?? "—") },
  ];

  return (
    <Card>
      <h2 className="font-semibold text-fg">{t("reports.operational.title")}</h2>
      <p className="text-fg-muted">{t("reports.operational.intro")}</p>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <SelectField
          id="operational-report-key"
          label={t("reports.operational.pickReport")}
          value={key}
          onChange={(e) => {
            setKey(e.target.value as OperationalReportKey);
            setResult(null);
          }}
          options={OPERATIONAL_REPORT_KEYS.map((k) => ({ value: k, label: t(`reports.${camelKey(k)}.title`) }))}
        />
        {key === "visitor-flow" && (
          <SelectField
            id="operational-report-grain"
            label={t("reports.operational.grain")}
            value={grain}
            onChange={(e) => setGrain(e.target.value as ReportGrain)}
            options={GRAINS.map((g) => ({ value: g, label: t(`reports.operational.grain.${g}`) }))}
          />
        )}
      </div>
      <p className="text-fg-muted">{t(`reports.${camelKey(key)}.intro`)}</p>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <TextField id="operational-report-from" label={t("reports.filters.from")} type="date" value={from} onChange={(e) => setFrom(e.target.value)} onBlur={validateRange} />
        <TextField
          id="operational-report-to"
          label={t("reports.filters.to")}
          type="date"
          value={to}
          onChange={(e) => setTo(e.target.value)}
          onBlur={validateRange}
          error={rangeValidation.message("range")}
        />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <SelectField
          id="operational-report-zone"
          label={t("reports.filters.zone")}
          value={zoneId}
          onChange={(e) => setZoneId(e.target.value)}
          options={[{ value: "", label: t("reports.filters.allZones") }, ...(zones.items ?? []).map((z) => ({ value: z.id, label: z.name }))]}
        />
        <SelectField
          id="operational-report-group"
          label={t("reports.filters.serviceGroup")}
          value={groupId}
          onChange={(e) => {
            setGroupId(e.target.value);
            setServiceId("");
          }}
          options={[{ value: "", label: t("reports.filters.allServiceGroups") }, ...(groups.items ?? []).map((g) => ({ value: g.id, label: nameOf(g.name_i18n) }))]}
        />
        <SelectField
          id="operational-report-service"
          label={t("reports.filters.service")}
          value={serviceId}
          onChange={(e) => setServiceId(e.target.value)}
          options={[{ value: "", label: t("reports.filters.allServices") }, ...(services.items ?? []).map((s) => ({ value: s.id, label: nameOf(s.name_i18n) }))]}
        />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <SelectField
          id="operational-report-agent"
          label={t("reports.filters.agent")}
          value={agentId}
          onChange={(e) => setAgentId(e.target.value)}
          options={[{ value: "", label: t("reports.filters.allAgents") }, ...(users.items ?? []).map((u) => ({ value: u.id, label: u.display_name ?? u.username }))]}
        />
        <SelectField
          id="operational-report-priority"
          label={t("reports.filters.priorityClass")}
          value={priorityClassId}
          onChange={(e) => setPriorityClassId(e.target.value)}
          options={[{ value: "", label: t("reports.filters.allPriorityClasses") }, ...(priorityClasses.items ?? []).map((c) => ({ value: c.id, label: nameOf(c.name_i18n) }))]}
        />
        <SelectField
          id="operational-report-channel"
          label={t("reports.filters.channel")}
          value={channel}
          onChange={(e) => setChannel(e.target.value as "" | Channel)}
          options={[{ value: "", label: t("reports.filters.allChannels") }, ...CHANNELS.map((c) => ({ value: c, label: t(`catalogue.channel.${c}`) }))]}
        />
        <TextField
          id="operational-report-visitor-category"
          label={t("reports.filters.visitorCategory")}
          value={visitorCategory}
          onChange={(e) => setVisitorCategory(e.target.value)}
        />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Button type="button" disabled={busy || !rangeChosen} onClick={() => void runReport()}>
          {t(busy ? "reports.operational.running" : "reports.operational.run")}
        </Button>
        {!rangeChosen && <span className="text-fg-muted">{t("reports.operational.rangeRequired")}</span>}
      </div>
      {error && <ErrorAlert>{error}</ErrorAlert>}
      {result && (
        <>
          <DataTable
            caption={t("reports.operational.rows", { grain: key === "visitor-flow" ? t(`reports.operational.grain.${grain}`) : label(meta.nameField) })}
            columns={rowsColumns}
            rows={result.rows.map((row, i) => ({ row, i }))}
            rowKey={(item) => String(item.i)}
            emptyState={<EmptyState title={t("reports.empty")} />}
          />

          <DataTable columns={totalsColumns} rows={totalsRows} rowKey={(row) => row.label} />

          {/* This breakdown can span every Service at the Site (no Service filter chosen), so it names each row by
              its raw id rather than a localised name — resolving that would need a Site-wide Service directory this
              card has no other reason to load; the primary rows table above already shows each Service's own
              localised name. */}
          {key === "service" && waitByHourBand.length > 0 && (
            <DataTable
              caption={t("reports.operational.waitByHourBand")}
              columns={waitByHourBandColumns}
              rows={waitByHourBand}
              rowKey={(row) => `${String(row["service_id"] ?? "")}-${String(row["hour_band"] ?? "")}`}
            />
          )}

          {key === "visitor-flow" && channelMix.length > 0 && (
            <DataTable
              caption={t("reports.operational.channelMix")}
              columns={channelMixColumns}
              rows={channelMix}
              rowKey={(row) => String(row["channel"])}
            />
          )}
        </>
      )}
    </Card>
  );
}

function camelKey(key: OperationalReportKey): string {
  return key.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase());
}
