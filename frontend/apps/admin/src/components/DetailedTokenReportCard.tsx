"use client";

import {
  CHANNELS,
  DETAILED_TOKEN_REPORT_KEY,
  type Channel,
  type DetailedTokenReportPage,
  type DetailedTokenReportRequest,
  type PriorityClass,
  type ReportSort,
  type ServiceEntry,
  type ServiceGroup,
  type Site,
  type SortDirection,
  type UserSummary,
  type Zone,
} from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField, TextField } from "@qms/ui";
import { useState } from "react";
import { describeError, localisedName, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

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

  return (
    <Card>
      <h2 className="qms-heading">{t("reports.detailedToken.title")}</h2>
      <p className="qms-muted">{t("reports.detailedToken.intro")}</p>
      <div className="qms-row">
        <TextField id="report-from" label={t("reports.filters.from")} type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        <TextField id="report-to" label={t("reports.filters.to")} type="date" value={to} onChange={(e) => setTo(e.target.value)} />
      </div>
      <div className="qms-row">
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
      <div className="qms-row">
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
      <div className="qms-row">
        <Button type="button" disabled={busy} onClick={() => runReport({ page: 0 })}>
          {t(busy ? "reports.running" : "reports.run")}
        </Button>
      </div>
      {error && <ErrorAlert>{error}</ErrorAlert>}
      {result && (
        <>
          <p role="status">{t("reports.summary", { total: result.total_rows, issued: result.tickets_issued })}</p>
          {result.rows.length === 0 ? (
            <p className="qms-muted">{t("reports.empty")}</p>
          ) : (
            <div className="qms-table-scroll">
              <table className="qms-table">
                <thead>
                  <tr>
                    {COLUMNS.map((column) => (
                      <th key={column.key} scope="col">
                        <button type="button" className="qms-button qms-button--secondary" onClick={() => sortBy(column.sort)}>
                          {t(`reports.detailedToken.col.${column.key}`)}
                          {sort === column.sort ? (direction === "asc" ? " ↑" : " ↓") : ""}
                        </button>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {result.rows.map((row) => (
                    <tr key={row.ticket_id}>
                      <th scope="row">{row.token}</th>
                      <td>{row.visitor_code ?? "—"}</td>
                      <td>{row.visitor_name ?? "—"}</td>
                      <td>{row.visitor_category ?? "—"}</td>
                      <td>{nameOf(row.service_group)}</td>
                      <td>{nameOf(row.service)}</td>
                      <td>{t(`catalogue.channel.${row.channel}`)}</td>
                      <td>{nameOf(row.priority_class)}</td>
                      <td>{dateTime(row.issue_time)}</td>
                      <td>{dateTime(row.call_time)}</td>
                      <td>{dateTime(row.start_time)}</td>
                      <td>{dateTime(row.end_time)}</td>
                      <td>{minutes(row.wait_seconds)}</td>
                      <td>{minutes(row.service_seconds)}</td>
                      <td>{row.counter ?? "—"}</td>
                      <td>{row.agent ?? "—"}</td>
                      <td>{Object.keys(row.outcome).length > 0 ? nameOf(row.outcome) : "—"}</td>
                      <td>{row.transfers}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div className="qms-row">
            <Button variant="secondary" type="button" disabled={busy || page === 0} onClick={() => runReport({ page: page - 1 })}>
              {t("reports.prevPage")}
            </Button>
            <span className="qms-muted">{t("reports.pageOf", { page: page + 1, total: Math.max(result.total_pages, 1) })}</span>
            <Button variant="secondary" type="button" disabled={busy || page + 1 >= result.total_pages} onClick={() => runReport({ page: page + 1 })}>
              {t("reports.nextPage")}
            </Button>
          </div>
        </>
      )}
    </Card>
  );
}
