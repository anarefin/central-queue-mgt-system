"use client";

import {
  CHANNELS,
  DETAILED_TOKEN_REPORT_KEY,
  type Channel,
  type DetailedTokenReportPage,
  type DetailedTokenReportRequest,
  type DetailedTokenReportRow,
  type PriorityClass,
  type ReportExportFormat,
  type ReportExportJob,
  type ReportSort,
  type ServiceEntry,
  type ServiceGroup,
  type Site,
  type SortDirection,
  type UserSummary,
  type Zone,
} from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Badge, Button, Card, DataTable, EmptyState, ErrorAlert, reportRange, SelectField, TextField, type DataTableColumn } from "@qms/ui";
import { useEffect, useRef, useState } from "react";
import { describeError, localisedName, useFormValidation, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

const EXPORT_FORMATS: ReportExportFormat[] = ["csv", "xlsx", "pdf"];
const JOB_POLL_MS = 2000;

/** Saves `blob` to disk under `filename`, the same object-URL-anchor trick every browser supports with no library. */
function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}

/** §16.1's own column order; each header names the {@link ReportSort} key clicking it sorts by (FR-RPT-002). */
const COLUMNS: { key: string; sort: ReportSort }[] = [
  { key: "token", sort: "token_number" },
  { key: "visitorCode", sort: "visitor_code" },
  { key: "visitorName", sort: "visitor_name" },
  { key: "category", sort: "visitor_category" },
  { key: "serviceGroup", sort: "service_group" },
  { key: "service", sort: "service" },
  { key: "channel", sort: "channel" },
  { key: "priority", sort: "priority_class" },
  { key: "issueTime", sort: "issued_at" },
  { key: "callTime", sort: "called_at" },
  { key: "startTime", sort: "served_at" },
  { key: "endTime", sort: "closed_at" },
  { key: "wait", sort: "wait_seconds" },
  { key: "serviceDuration", sort: "service_seconds" },
  { key: "counter", sort: "counter" },
  { key: "agent", sort: "agent" },
  { key: "outcome", sort: "outcome" },
  { key: "transfers", sort: "transfers" },
];

const SIZE = 50;

/**
 * The detailed token report (SRS §16.1, ticket 48): one row per ticket, filtered by date range, Site, Zone, Service
 * group, Service, Agent, Priority class, channel and visitor category (FR-RPT-001), server-side paged and sortable
 * by any displayed column (FR-RPT-002). Runs against the reporting store, which trails live tickets by under a
 * minute (FR-RPT-020) — never the live queue itself.
 */
export function DetailedTokenReportCard({ site }: { site: Site }) {
  const { t, language, formatDate, formatTime } = useI18n();
  const { client } = useApi();

  const zones = useList<Zone>(client ? () => client.sites.zones(site.id) : null, [client, site.id]);
  const groups = useList<ServiceGroup>(client ? () => client.catalogue.groups(site.id) : null, [client, site.id]);
  const priorityClasses = useList<PriorityClass>(client ? () => client.priority.classes() : null, [client]);
  const users = useList<UserSummary>(client ? () => client.users.list() : null, [client]);

  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [zoneId, setZoneId] = useState("");
  const [groupId, setGroupId] = useState("");
  const [serviceId, setServiceId] = useState("");
  const [agentId, setAgentId] = useState("");
  const [priorityClassId, setPriorityClassId] = useState("");
  const [channel, setChannel] = useState<"" | Channel>("");
  const [visitorCategory, setVisitorCategory] = useState("");

  const services = useList<ServiceEntry>(client && groupId ? () => client.catalogue.services(groupId) : null, [client, groupId]);

  const [page, setPage] = useState(0);
  const [sort, setSort] = useState<ReportSort>("issued_at");
  const [direction, setDirection] = useState<SortDirection>("desc");
  const [result, setResult] = useState<DetailedTokenReportPage | null>(null);
  const { busy, error, run } = useSubmit();

  const [format, setFormat] = useState<ReportExportFormat>("csv");
  const [job, setJob] = useState<ReportExportJob | null>(null);
  const exporting = useSubmit();
  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null);

  const rangeValidation = useFormValidation<{ from: string; to: string }>({ range: (v) => reportRange(v.from, v.to) }, { range: "report-to" });
  const validateRange = () => rangeValidation.validateField("range", { from, to });

  // FR-RPT-004: a background export is polled at GET /reports/jobs/{id} until it leaves queued/running, then its
  // finished file is pulled and handed to the browser the same way an inline export already is.
  useEffect(() => {
    if (!job || !client || (job.status !== "queued" && job.status !== "running")) return;
    pollTimer.current = setInterval(() => {
      void client.reports.job(job.id).then(
        (next) => {
          setJob(next);
          if (next.status === "done") {
            void client.reports.download(next.id).then((ready) => downloadBlob(ready.blob, ready.filename));
          }
        },
        () => setJob((current) => (current ? { ...current, status: "failed" } : current)),
      );
    }, JOB_POLL_MS);
    return () => {
      if (pollTimer.current) clearInterval(pollTimer.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job?.id, job?.status, client]);

  function exportFilter(): Omit<DetailedTokenReportRequest, "page" | "size" | "sort" | "direction"> {
    return {
      site_id: site.id,
      from: from ? new Date(from).toISOString() : undefined,
      to: to ? new Date(to).toISOString() : undefined,
      zone_id: zoneId || undefined,
      service_group_id: groupId || undefined,
      service_id: serviceId || undefined,
      agent_id: agentId || undefined,
      priority_class_id: priorityClassId || undefined,
      channel: channel || undefined,
      visitor_category: visitorCategory || undefined,
    };
  }

  async function exportReport() {
    if (!rangeValidation.validateAll({ from, to })) return;
    setJob(null);
    await exporting.run(async () => {
      const outcome = await client!.reports.export(DETAILED_TOKEN_REPORT_KEY, { ...exportFilter(), format });
      if (outcome.kind === "ready") {
        downloadBlob(outcome.blob, outcome.filename);
      } else {
        setJob({ id: outcome.jobId, report_key: DETAILED_TOKEN_REPORT_KEY, format, status: "queued", row_count: null, requested_at: new Date().toISOString(), completed_at: null, expires_at: null, error: null });
      }
    });
  }

  const nameOf = (names: Record<string, string>) => localisedName(names, language, site.default_language);
  const dateTime = (iso: string | null) => (iso ? `${formatDate(new Date(iso))} ${formatTime(new Date(iso))}` : "—");
  const minutes = (seconds: number | null) => (seconds === null ? "—" : Math.round(seconds / 60).toString());

  function request(overrides: Partial<{ page: number; sort: ReportSort; direction: SortDirection }>): DetailedTokenReportRequest {
    return {
      site_id: site.id,
      from: from ? new Date(from).toISOString() : undefined,
      to: to ? new Date(to).toISOString() : undefined,
      zone_id: zoneId || undefined,
      service_group_id: groupId || undefined,
      service_id: serviceId || undefined,
      agent_id: agentId || undefined,
      priority_class_id: priorityClassId || undefined,
      channel: channel || undefined,
      visitor_category: visitorCategory || undefined,
      page: overrides.page ?? page,
      size: SIZE,
      sort: overrides.sort ?? sort,
      direction: overrides.direction ?? direction,
    };
  }

  async function runReport(overrides: Partial<{ page: number; sort: ReportSort; direction: SortDirection }> = {}) {
    if (!rangeValidation.validateAll({ from, to })) return;
    await run(async () => {
      const next = request(overrides);
      setPage(next.page ?? 0);
      setSort(next.sort ?? "issued_at");
      setDirection(next.direction ?? "desc");
      setResult(await client!.reports.run(DETAILED_TOKEN_REPORT_KEY, next));
    });
  }

  function sortBy(column: ReportSort) {
    const nextDirection: SortDirection = sort === column && direction === "asc" ? "desc" : "asc";
    void runReport({ sort: column, direction: nextDirection, page: 0 });
  }

  const columns: DataTableColumn<DetailedTokenReportRow>[] = COLUMNS.map((column) => ({
    key: column.sort,
    header: t(`reports.detailedToken.col.${column.key}`),
    sortable: true,
    render: (row) => {
      switch (column.key) {
        case "token":
          return row.token;
        case "visitorCode":
          return row.visitor_code ?? "—";
        case "visitorName":
          return row.visitor_name ?? "—";
        case "category":
          return row.visitor_category ?? "—";
        case "serviceGroup":
          return nameOf(row.service_group);
        case "service":
          return nameOf(row.service);
        case "channel":
          return t(`catalogue.channel.${row.channel}`);
        case "priority":
          return nameOf(row.priority_class);
        case "issueTime":
          return dateTime(row.issue_time);
        case "callTime":
          return dateTime(row.call_time);
        case "startTime":
          return dateTime(row.start_time);
        case "endTime":
          return dateTime(row.end_time);
        case "wait":
          return minutes(row.wait_seconds);
        case "serviceDuration":
          return minutes(row.service_seconds);
        case "counter":
          return row.counter ?? "—";
        case "agent":
          return row.agent ?? "—";
        case "outcome":
          return Object.keys(row.outcome).length > 0 ? nameOf(row.outcome) : "—";
        case "transfers":
          return row.transfers;
        default:
          return null;
      }
    },
  }));

  return (
    <Card>
      <h2 className="font-semibold text-fg">{t("reports.detailedToken.title")}</h2>
      <p className="text-fg-muted">{t("reports.detailedToken.intro")}</p>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <TextField id="report-from" label={t("reports.filters.from")} type="date" value={from} onChange={(e) => setFrom(e.target.value)} onBlur={validateRange} />
        <TextField
          id="report-to"
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
          id="report-zone"
          label={t("reports.filters.zone")}
          value={zoneId}
          onChange={(e) => setZoneId(e.target.value)}
          options={[{ value: "", label: t("reports.filters.allZones") }, ...(zones.items ?? []).map((z) => ({ value: z.id, label: z.name }))]}
        />
        <SelectField
          id="report-group"
          label={t("reports.filters.serviceGroup")}
          value={groupId}
          onChange={(e) => {
            setGroupId(e.target.value);
            setServiceId("");
          }}
          options={[{ value: "", label: t("reports.filters.allServiceGroups") }, ...(groups.items ?? []).map((g) => ({ value: g.id, label: nameOf(g.name_i18n) }))]}
        />
        <SelectField
          id="report-service"
          label={t("reports.filters.service")}
          value={serviceId}
          onChange={(e) => setServiceId(e.target.value)}
          options={[{ value: "", label: t("reports.filters.allServices") }, ...(services.items ?? []).map((s) => ({ value: s.id, label: nameOf(s.name_i18n) }))]}
        />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <SelectField
          id="report-agent"
          label={t("reports.filters.agent")}
          value={agentId}
          onChange={(e) => setAgentId(e.target.value)}
          options={[{ value: "", label: t("reports.filters.allAgents") }, ...(users.items ?? []).map((u) => ({ value: u.id, label: u.display_name ?? u.username }))]}
        />
        <SelectField
          id="report-priority"
          label={t("reports.filters.priorityClass")}
          value={priorityClassId}
          onChange={(e) => setPriorityClassId(e.target.value)}
          options={[{ value: "", label: t("reports.filters.allPriorityClasses") }, ...(priorityClasses.items ?? []).map((c) => ({ value: c.id, label: nameOf(c.name_i18n) }))]}
        />
        <SelectField
          id="report-channel"
          label={t("reports.filters.channel")}
          value={channel}
          onChange={(e) => setChannel(e.target.value as "" | Channel)}
          options={[{ value: "", label: t("reports.filters.allChannels") }, ...CHANNELS.map((c) => ({ value: c, label: t(`catalogue.channel.${c}`) }))]}
        />
        <TextField id="report-visitor-category" label={t("reports.filters.visitorCategory")} value={visitorCategory} onChange={(e) => setVisitorCategory(e.target.value)} />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Button type="button" disabled={busy} onClick={() => runReport({ page: 0 })}>
          {t(busy ? "reports.running" : "reports.run")}
        </Button>
        <SelectField
          id="report-export-format"
          label={t("reports.export.format")}
          value={format}
          onChange={(e) => setFormat(e.target.value as ReportExportFormat)}
          options={EXPORT_FORMATS.map((f) => ({ value: f, label: t(`reports.export.${f}`) }))}
        />
        <Button type="button" variant="secondary" disabled={exporting.busy || job?.status === "queued" || job?.status === "running"} onClick={() => void exportReport()}>
          {t(exporting.busy ? "reports.export.exporting" : "reports.export.button")}
        </Button>
      </div>
      {error && <ErrorAlert>{error}</ErrorAlert>}
      {exporting.error && <ErrorAlert>{exporting.error}</ErrorAlert>}
      {job && (job.status === "queued" || job.status === "running") && (
        <p role="status" className="flex items-center gap-2">
          <Badge variant="info">{t("reports.export.status.queued")}</Badge> {t("reports.export.queued")}
        </p>
      )}
      {job && job.status === "done" && (
        <p role="status" className="flex items-center gap-2">
          <Badge variant="ok">{t("reports.export.status.ready")}</Badge> {t("reports.export.ready")}
        </p>
      )}
      {job && job.status === "failed" && (
        <ErrorAlert>
          <span className="flex items-center gap-2">
            <Badge variant="danger">{t("reports.export.status.failed")}</Badge> {t("reports.export.failed")}
          </span>
        </ErrorAlert>
      )}
      {result && (
        <>
          <p role="status">{t("reports.summary", { total: result.total_rows, issued: result.tickets_issued })}</p>
          <DataTable
            columns={columns}
            rows={result.rows}
            rowKey={(row) => row.ticket_id}
            sort={{ key: sort, direction: direction === "asc" ? "ascending" : "descending" }}
            onSortChange={(key) => sortBy(key as ReportSort)}
            emptyState={<EmptyState title={t("reports.empty")} />}
          />
          <div className="flex flex-wrap items-center justify-between gap-3">
            <Button variant="secondary" type="button" disabled={busy || page === 0} onClick={() => runReport({ page: page - 1 })}>
              {t("reports.prevPage")}
            </Button>
            <span className="text-fg-muted">{t("reports.pageOf", { page: page + 1, total: Math.max(result.total_pages, 1) })}</span>
            <Button variant="secondary" type="button" disabled={busy || page + 1 >= result.total_pages} onClick={() => runReport({ page: page + 1 })}>
              {t("reports.nextPage")}
            </Button>
          </div>
        </>
      )}
    </Card>
  );
}
