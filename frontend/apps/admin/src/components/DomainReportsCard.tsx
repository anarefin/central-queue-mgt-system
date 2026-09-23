"use client";

import {
  DOMAIN_REPORT_KEYS,
  type AuditReportEntry,
  type AuditReportPage,
  type DomainReportKey,
  type DomainReportPage,
  type DomainReportRow,
  type DomainReportValue,
  type ServiceEntry,
  type ServiceGroup,
  type Site,
  type UserSummary,
} from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, reportRange, SelectField, TextField } from "@qms/ui";
import { useState } from "react";
import { localisedName, useFormValidation, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

type CatalogueKey = DomainReportKey | "audit";

const ALL_KEYS: CatalogueKey[] = [...DOMAIN_REPORT_KEYS, "audit"];

/** Which columns show, in order, for each of the four row-shaped report keys (§16.1's own catalogue). Audit is
 * rendered separately below, since it comes back in {@link AuditReportPage}'s own shape, not {@link DomainReportPage}. */
const COLUMNS: Record<DomainReportKey, string[]> = {
  appointment: ["reference_code", "service_name", "agent_name", "visitor_category", "source", "state", "slot_at", "checked_in_at", "lead_time_seconds", "no_show"],
  journey: ["site_name", "started_at", "ended_at", "stops_planned", "stops_completed", "total_time_on_site_seconds"],
  feedback: ["rating", "comment", "comment_approved", "agent_name", "service_name", "submitted_at"],
  notification: ["trigger_key", "channel", "status", "cost_indicator", "service_name", "created_at", "sent_at"],
};

/** §16.1's own aggregate for `appointment` (no-show rate per Service/Agent/visitor category, FR-APT-043, plus
 * adherence) and `journey` (completion rate and per-stop wait, FR-QUE-064) — neither `feedback` nor `notification`
 * carries a comparable per-group breakdown, so their own `extra` (average rating / nothing) is skipped here. */
const NO_SHOW_EXTRA_COLUMNS = ["name", "total", "no_show", "no_show_rate_pct"];

function camel(key: string): string {
  return key.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase());
}

/**
 * The four row-shaped reports ticket 51 adds (§16.1: appointment, journey, feedback, notification) plus the Audit
 * report, all under `POST /reports/{key}/run`. Appointment and journey close the two §15.3 KPIs ticket 50 left out
 * (appointment adherence, journey completion) — both render in this card's own "extra" section. Audit reuses the
 * existing `/audit` read (cursor-paged, not `page`/`size`) and is additionally gated on `audit:read`: a Team Admin
 * who can run every other report here gets a forbidden error for this one, shown the same way any other failed
 * call is (SRS §20.3).
 */
export function DomainReportsCard({ site }: { site: Site }) {
  const { t, language, formatDate, formatTime } = useI18n();
  const { client } = useApi();

  const groups = useList<ServiceGroup>(client ? () => client.catalogue.groups(site.id) : null, [client, site.id]);
  const users = useList<UserSummary>(client ? () => client.users.list() : null, [client]);

  const [key, setKey] = useState<CatalogueKey>("appointment");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [groupId, setGroupId] = useState("");
  const [serviceId, setServiceId] = useState("");
  const [agentId, setAgentId] = useState("");
  const [visitorCategory, setVisitorCategory] = useState("");

  const services = useList<ServiceEntry>(client && groupId ? () => client.catalogue.services(groupId) : null, [client, groupId]);

  const [result, setResult] = useState<DomainReportPage | null>(null);
  const [auditResult, setAuditResult] = useState<AuditReportPage | null>(null);
  const { busy, error, run } = useSubmit();

  const rangeValidation = useFormValidation<{ from: string; to: string }>(
    { range: (v) => reportRange(v.from, v.to) },
    { range: "domain-report-to" },
  );
  const validateRange = () => rangeValidation.validateField("range", { from, to });

  const nameOf = (names: Record<string, string>) => localisedName(names, language, site.default_language);
  const showsGroupAndService = key === "appointment" || key === "feedback";
  const showsAgent = key !== "notification" && key !== "audit";

  async function runReport() {
    if (!rangeValidation.validateAll({ from, to })) return;
    await run(async () => {
      if (key === "audit") {
        const page = await client!.reports.runAudit({
          from: from ? new Date(from).toISOString() : undefined,
          to: to ? new Date(to).toISOString() : undefined,
        });
        setAuditResult(page);
        setResult(null);
        return;
      }
      const page = await client!.reports.runDomain(key, {
        site_id: site.id,
        from: from ? new Date(from).toISOString() : undefined,
        to: to ? new Date(to).toISOString() : undefined,
        service_group_id: showsGroupAndService ? groupId || undefined : undefined,
        service_id: showsGroupAndService ? serviceId || undefined : undefined,
        agent_id: showsAgent ? agentId || undefined : undefined,
        visitor_category: visitorCategory || undefined,
      });
      setResult(page);
      setAuditResult(null);
    });
  }

  async function loadMoreAudit() {
    await run(async () => {
      const page = await client!.reports.runAudit({
        from: from ? new Date(from).toISOString() : undefined,
        to: to ? new Date(to).toISOString() : undefined,
        cursor: auditResult?.next_cursor ?? undefined,
      });
      setAuditResult((prev) => (prev ? { ...page, items: [...prev.items, ...page.items] } : page));
    });
  }

  function label(field: string): string {
    return t(`reports.metric.${field}`);
  }

  function formatValue(field: string, value: DomainReportValue): string {
    if (value === null || value === undefined) return "—";
    if (typeof value === "boolean") return t(value ? "reports.value.yes" : "reports.value.no");
    if (field.endsWith("_seconds")) return Math.round(Number(value) / 60).toString();
    if (field.endsWith("_pct")) return `${Number(value).toFixed(1)}%`;
    if (field.endsWith("_at") && typeof value === "string") return `${formatDate(new Date(value))} ${formatTime(new Date(value))}`;
    if (typeof value === "object") return nameOf(value as Record<string, string>);
    return String(value);
  }

  function extraTable(title: string, rows: DomainReportRow[]) {
    if (rows.length === 0) return null;
    return (
      <div className="overflow-x-auto" key={title}>
        <table className="w-full border-collapse text-sm [font-variant-numeric:tabular-nums] [&_th]:border-b [&_th]:border-border [&_th]:px-2.5 [&_th]:py-1.5 [&_th]:text-start [&_td]:border-b [&_td]:border-border [&_td]:px-2.5 [&_td]:py-1.5 [&_td]:text-start">
          <caption>{title}</caption>
          <thead>
            <tr>
              {NO_SHOW_EXTRA_COLUMNS.map((c) => (
                <th key={c} scope="col">
                  {label(c)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <tr key={i}>
                {NO_SHOW_EXTRA_COLUMNS.map((c) => (
                  <td key={c}>{formatValue(c, row[c] ?? null)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  const columns = key === "audit" ? [] : COLUMNS[key];
  const extra = result?.extra ?? null;

  return (
    <Card>
      <h2 className="font-semibold text-fg">{t("reports.domain.title")}</h2>
      <p className="text-fg-muted">{t("reports.domain.intro")}</p>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <SelectField
          id="domain-report-key"
          label={t("reports.domain.pickReport")}
          value={key}
          onChange={(e) => {
            setKey(e.target.value as CatalogueKey);
            setResult(null);
            setAuditResult(null);
          }}
          options={ALL_KEYS.map((k) => ({ value: k, label: t(`reports.${camel(k)}.title`) }))}
        />
      </div>
      <p className="text-fg-muted">{t(`reports.${camel(key)}.intro`)}</p>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <TextField id="domain-report-from" label={t("reports.filters.from")} type="date" value={from} onChange={(e) => setFrom(e.target.value)} onBlur={validateRange} />
        <TextField
          id="domain-report-to"
          label={t("reports.filters.to")}
          type="date"
          value={to}
          onChange={(e) => setTo(e.target.value)}
          onBlur={validateRange}
          error={rangeValidation.message("range")}
        />
      </div>
      {key !== "audit" && (
        <div className="flex flex-wrap items-center justify-between gap-3">
          {showsGroupAndService && (
            <>
              <SelectField
                id="domain-report-group"
                label={t("reports.filters.serviceGroup")}
                value={groupId}
                onChange={(e) => {
                  setGroupId(e.target.value);
                  setServiceId("");
                }}
                options={[{ value: "", label: t("reports.filters.allServiceGroups") }, ...(groups.items ?? []).map((g) => ({ value: g.id, label: nameOf(g.name_i18n) }))]}
              />
              <SelectField
                id="domain-report-service"
                label={t("reports.filters.service")}
                value={serviceId}
                onChange={(e) => setServiceId(e.target.value)}
                options={[{ value: "", label: t("reports.filters.allServices") }, ...(services.items ?? []).map((s) => ({ value: s.id, label: nameOf(s.name_i18n) }))]}
              />
            </>
          )}
          {showsAgent && (
            <SelectField
              id="domain-report-agent"
              label={t("reports.filters.agent")}
              value={agentId}
              onChange={(e) => setAgentId(e.target.value)}
              options={[{ value: "", label: t("reports.filters.allAgents") }, ...(users.items ?? []).map((u) => ({ value: u.id, label: u.display_name ?? u.username }))]}
            />
          )}
          <TextField id="domain-report-visitor-category" label={t("reports.filters.visitorCategory")} value={visitorCategory} onChange={(e) => setVisitorCategory(e.target.value)} />
        </div>
      )}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Button type="button" disabled={busy} onClick={() => void runReport()}>
          {t(busy ? "reports.domain.running" : "reports.domain.run")}
        </Button>
      </div>
      {error && <ErrorAlert>{error}</ErrorAlert>}

      {key !== "audit" && result && (
        <>
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-sm [font-variant-numeric:tabular-nums] [&_th]:border-b [&_th]:border-border [&_th]:px-2.5 [&_th]:py-1.5 [&_th]:text-start [&_td]:border-b [&_td]:border-border [&_td]:px-2.5 [&_td]:py-1.5 [&_td]:text-start">
              <caption>{t("reports.domain.rows", { count: result.total_rows })}</caption>
              <thead>
                <tr>
                  {columns.map((c) => (
                    <th key={c} scope="col">
                      {label(c)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {result.rows.length === 0 ? (
                  <tr>
                    <td colSpan={columns.length} className="text-fg-muted">
                      {t("reports.empty")}
                    </td>
                  </tr>
                ) : (
                  result.rows.map((row, i) => (
                    <tr key={i}>
                      {columns.map((c) => (
                        <td key={c}>{formatValue(c, row[c] ?? null)}</td>
                      ))}
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>

          {key === "appointment" && extra && (
            <>
              {extraTable(t("reports.domain.extra.noShowByService"), (extra["no_show_rate_by_service"] as DomainReportRow[]) ?? [])}
              {extraTable(t("reports.domain.extra.noShowByAgent"), (extra["no_show_rate_by_agent"] as DomainReportRow[]) ?? [])}
              {extraTable(t("reports.domain.extra.noShowByVisitorCategory"), (extra["no_show_rate_by_visitor_category"] as DomainReportRow[]) ?? [])}
              {extra["adherence"] && (
                <p className="text-fg-muted">
                  {t("reports.domain.extra.adherence", { pct: String(formatValue("adherence_pct", (extra["adherence"] as DomainReportRow)["adherence_pct"] ?? null)) })}
                </p>
              )}
            </>
          )}

          {key === "journey" && extra && (
            <p className="text-fg-muted">
              {t("reports.domain.extra.journeyCompletion", {
                pct: String(formatValue("completion_rate_pct", extra["completion_rate_pct"] as DomainReportValue)),
                wait: String(formatValue("avg_stop_wait_seconds", extra["avg_stop_wait_seconds"] as DomainReportValue)),
              })}
            </p>
          )}

          {key === "feedback" && extra && (
            <p className="text-fg-muted">
              {t("reports.domain.extra.avgRating", { rating: String(formatValue("avg_rating", extra["avg_rating"] as DomainReportValue)) })}
            </p>
          )}
        </>
      )}

      {key === "audit" && auditResult && (
        <>
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-sm [font-variant-numeric:tabular-nums] [&_th]:border-b [&_th]:border-border [&_th]:px-2.5 [&_th]:py-1.5 [&_th]:text-start [&_td]:border-b [&_td]:border-border [&_td]:px-2.5 [&_td]:py-1.5 [&_td]:text-start">
              <caption>{t("reports.audit.title")}</caption>
              <thead>
                <tr>
                  <th scope="col">{label("actor_role")}</th>
                  <th scope="col">{label("action")}</th>
                  <th scope="col">{label("entity")}</th>
                  <th scope="col">{label("occurred_at")}</th>
                </tr>
              </thead>
              <tbody>
                {auditResult.items.length === 0 ? (
                  <tr>
                    <td colSpan={4} className="text-fg-muted">
                      {t("reports.empty")}
                    </td>
                  </tr>
                ) : (
                  auditResult.items.map((entry: AuditReportEntry) => (
                    <tr key={entry.id}>
                      <td>{entry.actor_role ?? "—"}</td>
                      <td>{entry.action}</td>
                      <td>{entry.entity}</td>
                      <td>{formatValue("occurred_at", entry.occurred_at)}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
          {auditResult.next_cursor && (
            <Button type="button" disabled={busy} onClick={() => void loadMoreAudit()}>
              {t("reports.audit.loadMore")}
            </Button>
          )}
        </>
      )}
    </Card>
  );
}
