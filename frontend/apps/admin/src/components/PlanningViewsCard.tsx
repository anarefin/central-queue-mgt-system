"use client";

import {
  CHANNELS,
  PLANNING_VIEW_KEYS,
  type Channel,
  type PeakHoursResponse,
  type PlanningViewKey,
  type PriorityClass,
  type ServiceEntry,
  type ServiceGroup,
  type Site,
  type StaffingGapResponse,
  type UserSummary,
} from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField, TextField } from "@qms/ui";
import { useState } from "react";
import { localisedName, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

const DAY_KEYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];

function camel(key: string): string {
  return key.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase());
}

/**
 * The two staffing-planning views ticket 51 adds (§16.2): peak-hours (FR-RPT-011, ticket volume by hour-of-day x
 * ISO day-of-week) and staffing-gap (FR-RPT-012, tickets offered vs counter-hours available vs SLA attainment per
 * hour band). Both are a snapshot over one requested (mandatory) period, not a period-vs-period comparison.
 */
export function PlanningViewsCard({ site }: { site: Site }) {
  const { t, language } = useI18n();
  const { client } = useApi();

  const groups = useList<ServiceGroup>(client ? () => client.catalogue.groups(site.id) : null, [client, site.id]);
  const priorityClasses = useList<PriorityClass>(client ? () => client.priority.classes() : null, [client]);
  const users = useList<UserSummary>(client ? () => client.users.list() : null, [client]);

  const [key, setKey] = useState<PlanningViewKey>("peak-hours");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [groupId, setGroupId] = useState("");
  const [serviceId, setServiceId] = useState("");
  const [agentId, setAgentId] = useState("");
  const [priorityClassId, setPriorityClassId] = useState("");
  const [channel, setChannel] = useState<"" | Channel>("");
  const [visitorCategory, setVisitorCategory] = useState("");

  const services = useList<ServiceEntry>(client && groupId ? () => client.catalogue.services(groupId) : null, [client, groupId]);

  const [result, setResult] = useState<PeakHoursResponse | StaffingGapResponse | null>(null);
  const { busy, error, run } = useSubmit();

  const nameOf = (names: Record<string, string>) => localisedName(names, language, site.default_language);
  const rangeChosen = from !== "" && to !== "";

  async function runView() {
    await run(async () => {
      const response = await client!.reports.runPlanningView(key, {
        site_id: site.id,
        from: new Date(from).toISOString(),
        to: new Date(to).toISOString(),
        service_group_id: groupId || undefined,
        service_id: serviceId || undefined,
        agent_id: agentId || undefined,
        priority_class_id: priorityClassId || undefined,
        channel: channel || undefined,
        visitor_category: visitorCategory || undefined,
      });
      setResult(response);
    });
  }

  function formatPct(value: number | null): string {
    return value === null || value === undefined ? "—" : `${value.toFixed(1)}%`;
  }

  /** {@link PeakHoursResponse}'s cells are one per hour-of-day x day-of-week combination that had a ticket
   * (missing combinations mean zero); this renders the full 24 x 7 grid so an empty cell reads as zero, not absent. */
  function peakHoursGrid(response: PeakHoursResponse) {
    const byCell = new Map<string, number>();
    for (const cell of response.cells) byCell.set(`${cell.day_of_week}:${cell.hour_of_day}`, cell.ticket_count);
    return (
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-sm [font-variant-numeric:tabular-nums] [&_th]:border-b [&_th]:border-border [&_th]:px-2.5 [&_th]:py-1.5 [&_th]:text-start [&_td]:border-b [&_td]:border-border [&_td]:px-2.5 [&_td]:py-1.5 [&_td]:text-start">
          <caption>{t("reports.peakHours.grid")}</caption>
          <thead>
            <tr>
              <th scope="col">{t("reports.peakHours.hour")}</th>
              {DAY_KEYS.map((d) => (
                <th key={d} scope="col">
                  {t(`reports.peakHours.day.${d}`)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {Array.from({ length: 24 }, (_, hour) => (
              <tr key={hour}>
                <th scope="row">{hour}</th>
                {DAY_KEYS.map((_, i) => {
                  const dayOfWeek = i + 1;
                  return <td key={dayOfWeek}>{byCell.get(`${dayOfWeek}:${hour}`) ?? 0}</td>;
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  function staffingGapTable(response: StaffingGapResponse) {
    return (
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-sm [font-variant-numeric:tabular-nums] [&_th]:border-b [&_th]:border-border [&_th]:px-2.5 [&_th]:py-1.5 [&_th]:text-start [&_td]:border-b [&_td]:border-border [&_td]:px-2.5 [&_td]:py-1.5 [&_td]:text-start">
          <caption>{t("reports.staffingGap.title")}</caption>
          <thead>
            <tr>
              <th scope="col">{t("reports.staffingGap.hour")}</th>
              <th scope="col">{t("reports.metric.tickets_offered")}</th>
              <th scope="col">{t("reports.metric.counter_hours_available")}</th>
              <th scope="col">{t("reports.metric.sla_attainment_pct")}</th>
            </tr>
          </thead>
          <tbody>
            {response.rows.map((row) => (
              <tr key={row.hour_of_day}>
                <th scope="row">{row.hour_of_day}</th>
                <td>{row.tickets_offered}</td>
                <td>{row.counter_hours_available}</td>
                <td>{formatPct(row.sla_attainment_pct)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  return (
    <Card>
      <h2 className="font-semibold text-fg">{t("reports.planning.title")}</h2>
      <p className="text-fg-muted">{t("reports.planning.intro")}</p>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <SelectField
          id="planning-view-key"
          label={t("reports.planning.pickView")}
          value={key}
          onChange={(e) => {
            setKey(e.target.value as PlanningViewKey);
            setResult(null);
          }}
          options={PLANNING_VIEW_KEYS.map((k) => ({ value: k, label: t(`reports.${camel(k)}.title`) }))}
        />
      </div>
      <p className="text-fg-muted">{t(`reports.${camel(key)}.intro`)}</p>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <TextField id="planning-view-from" label={t("reports.filters.from")} type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        <TextField id="planning-view-to" label={t("reports.filters.to")} type="date" value={to} onChange={(e) => setTo(e.target.value)} />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <SelectField
          id="planning-view-group"
          label={t("reports.filters.serviceGroup")}
          value={groupId}
          onChange={(e) => {
            setGroupId(e.target.value);
            setServiceId("");
          }}
          options={[{ value: "", label: t("reports.filters.allServiceGroups") }, ...(groups.items ?? []).map((g) => ({ value: g.id, label: nameOf(g.name_i18n) }))]}
        />
        <SelectField
          id="planning-view-service"
          label={t("reports.filters.service")}
          value={serviceId}
          onChange={(e) => setServiceId(e.target.value)}
          options={[{ value: "", label: t("reports.filters.allServices") }, ...(services.items ?? []).map((s) => ({ value: s.id, label: nameOf(s.name_i18n) }))]}
        />
        <SelectField
          id="planning-view-agent"
          label={t("reports.filters.agent")}
          value={agentId}
          onChange={(e) => setAgentId(e.target.value)}
          options={[{ value: "", label: t("reports.filters.allAgents") }, ...(users.items ?? []).map((u) => ({ value: u.id, label: u.display_name ?? u.username }))]}
        />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <SelectField
          id="planning-view-priority"
          label={t("reports.filters.priorityClass")}
          value={priorityClassId}
          onChange={(e) => setPriorityClassId(e.target.value)}
          options={[{ value: "", label: t("reports.filters.allPriorityClasses") }, ...(priorityClasses.items ?? []).map((c) => ({ value: c.id, label: nameOf(c.name_i18n) }))]}
        />
        <SelectField
          id="planning-view-channel"
          label={t("reports.filters.channel")}
          value={channel}
          onChange={(e) => setChannel(e.target.value as "" | Channel)}
          options={[{ value: "", label: t("reports.filters.allChannels") }, ...CHANNELS.map((c) => ({ value: c, label: t(`catalogue.channel.${c}`) }))]}
        />
        <TextField id="planning-view-visitor-category" label={t("reports.filters.visitorCategory")} value={visitorCategory} onChange={(e) => setVisitorCategory(e.target.value)} />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Button type="button" disabled={busy || !rangeChosen} onClick={() => void runView()}>
          {t(busy ? "reports.planning.running" : "reports.planning.run")}
        </Button>
        {!rangeChosen && <span className="text-fg-muted">{t("reports.planning.rangeRequired")}</span>}
      </div>
      {error && <ErrorAlert>{error}</ErrorAlert>}
      {result && result.key === "peak-hours" && peakHoursGrid(result)}
      {result && result.key === "staffing-gap" && staffingGapTable(result)}
    </Card>
  );
}
