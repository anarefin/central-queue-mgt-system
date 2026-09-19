"use client";

import { QUEUE_STRATEGIES, type QueueDryRun, type QueueStrategy, type Site, type SiteServices } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField } from "@qms/ui";
import { useState } from "react";
import { describeError, localisedName, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

type OfferedService = SiteServices["items"][number];

/**
 * The dry run (FR-QUE-023): the computed order of one service's queue with each term of each score, so priority
 * settings can be checked without serving anyone. Another strategy can be tried without saving it.
 */
export function DryRunCard({ site }: { site: Site }) {
  const { t, language, formatNumber, formatTime } = useI18n();
  const { client } = useApi();
  const services = useList<OfferedService>(client ? async () => ({ items: (await client.sites.services(site.id)).items }) : null, [client, site.id]);
  const [serviceId, setServiceId] = useState("");
  const [strategy, setStrategy] = useState<"" | QueueStrategy>("");
  const [result, setResult] = useState<QueueDryRun | null>(null);
  const { busy, error, run } = useSubmit();

  const selected = serviceId || services.items?.[0]?.id || "";
  const nameOf = (names: Record<string, string>) => localisedName(names, language, site.default_language);
  const classOf = (names: Record<string, string> | undefined) => (names ? nameOf(names) : "—");

  async function dryRun() {
    await run(async () => setResult(await client!.queues.dryRun(selected, strategy === "" ? undefined : strategy)));
  }

  return (
    <Card>
      <h2 className="qms-heading">{t("priority.dryRun.title")}</h2>
      <p className="qms-muted">{t("priority.dryRun.intro")}</p>
      {services.error !== null && <ErrorAlert>{describeError(t, services.error)}</ErrorAlert>}
      {services.items?.length === 0 && <p className="qms-muted">{t("reception.services.none")}</p>}
      {services.items && services.items.length > 0 && (
        <>
          <SelectField
            id="dryrun-service"
            label={t("priority.dryRun.service")}
            value={selected}
            onChange={(event) => {
              setServiceId(event.target.value);
              setResult(null);
            }}
            options={services.items.map((s) => ({ value: s.id, label: `${nameOf(s.name_i18n)} (${nameOf(s.service_group.name_i18n)})` }))}
          />
          <SelectField
            id="dryrun-strategy"
            label={t("priority.dryRun.strategy")}
            value={strategy}
            onChange={(event) => setStrategy(event.target.value as "" | QueueStrategy)}
            options={[
              { value: "", label: t("priority.dryRun.groupStrategy") },
              ...QUEUE_STRATEGIES.map((value) => ({ value, label: t(`priority.strategy.${value}`) })),
            ]}
          />
          <div className="qms-row">
            <Button type="button" disabled={busy || !selected} onClick={dryRun}>
              {t(busy ? "priority.dryRun.running" : "priority.dryRun.run")}
            </Button>
          </div>
        </>
      )}
      {error && <ErrorAlert>{error}</ErrorAlert>}
      {result && (
        <>
          <p role="status">
            {t("priority.dryRun.summary", {
              count: result.waiting_count,
              strategy: t(`priority.strategy.${result.strategy}`),
              time: formatTime(new Date(result.computed_at)),
            })}
          </p>
          <p className="qms-muted">{t("priority.dryRun.note")}</p>
          {result.tickets.length === 0 ? (
            <p className="qms-muted">{t("priority.dryRun.empty")}</p>
          ) : (
            <div className="qms-table-scroll">
            <table className="qms-table">
              <thead>
                <tr>
                  {["position", "token", "class", "wait", "headstart", "appointment", "escalation", "adjustment", "score", "status"].map((column) => (
                    <th key={column} scope="col">
                      {t(`priority.dryRun.col.${column}`)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {result.tickets.map((ticket) => (
                  <tr key={ticket.id}>
                    <td>{ticket.position}</td>
                    <th scope="row">{ticket.token_number}</th>
                    <td>{classOf(ticket.priority_class?.name_i18n)}</td>
                    <td>{formatNumber(ticket.terms.effective_wait_minutes)}</td>
                    <td>{formatNumber(ticket.terms.headstart_minutes)}</td>
                    <td>{formatNumber(ticket.terms.appointment_bonus)}</td>
                    <td>{formatNumber(ticket.terms.escalation_bonus)}</td>
                    <td>{formatNumber(ticket.terms.score_adjustment_minutes)}</td>
                    <td>{formatNumber(ticket.score)}</td>
                    <td>
                      {ticket.escalated && <span className="qms-warning">{t("priority.dryRun.escalated")}</span>}
                      {ticket.terms.adjustment_overridden && <span className="qms-muted"> {t("priority.dryRun.overridden")}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
          )}
        </>
      )}
    </Card>
  );
}
