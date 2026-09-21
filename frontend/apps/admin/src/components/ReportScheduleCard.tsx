"use client";

import { SCHEDULABLE_REPORT_KEYS, type ReportScheduleCadence, type ReportScheduleDelivery, type ReportExportFormat, type ReportSchedule, type Site } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField, TextField } from "@qms/ui";
import { useId, useState } from "react";
import { describeError, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

const CADENCES: ReportScheduleCadence[] = ["daily", "weekly", "monthly"];
const FORMATS: ReportExportFormat[] = ["csv", "xlsx", "pdf"];

function parseRecipients(raw: string): string[] {
  return [...new Set(raw.split(/[\n,]/).map((line) => line.trim()).filter((line) => line.length > 0))];
}

/**
 * Scheduled report delivery (ticket 52, SRS §16, FR-RPT-005): any report key in the catalogue above, emailed to a
 * named list daily, weekly or monthly, in a chosen format. Creating or changing a schedule is permission-checked and
 * dry-run validated server-side (`ReportScheduleService`); this card only picks the report, cadence, format and
 * recipients, and shows each schedule's own delivery log ("failures visible in the delivery log").
 */
export function ReportScheduleCard({ site }: { site: Site }) {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const schedules = useList<ReportSchedule>(client ? () => client.reports.schedules.list() : null, [client]);

  const [reportKey, setReportKey] = useState(SCHEDULABLE_REPORT_KEYS[0] ?? "detailed-token");
  const [cadence, setCadence] = useState<ReportScheduleCadence>("daily");
  const [format, setFormat] = useState<ReportExportFormat>("csv");
  const [recipientsText, setRecipientsText] = useState("");
  const { busy, error, run } = useSubmit();

  async function create() {
    if (!client) return;
    const ok = await run(() =>
      client.reports.schedules.create({
        report_key: reportKey,
        cadence,
        format,
        recipients: parseRecipients(recipientsText),
        filter: { site_id: site.id },
      }),
    );
    if (ok) {
      setRecipientsText("");
      schedules.reload();
    }
  }

  return (
    <Card>
      <h2 className="qms-heading">{t("reports.schedule.title")}</h2>
      <p className="qms-muted">{t("reports.schedule.intro")}</p>

      {schedules.error !== null && <ErrorAlert>{describeError(t, schedules.error)}</ErrorAlert>}
      {schedules.items?.length === 0 && <p className="qms-muted">{t("reports.schedule.empty")}</p>}
      {schedules.items && schedules.items.length > 0 && (
        <ul className="qms-list">
          {schedules.items.map((schedule) => (
            <ScheduleRow key={schedule.id} schedule={schedule} onChanged={schedules.reload} />
          ))}
        </ul>
      )}

      <div className="qms-stack">
        <div className="qms-row">
          <SelectField
            id={`${id}-key`}
            label={t("reports.schedule.reportKey")}
            value={reportKey}
            onChange={(event) => setReportKey(event.target.value)}
            options={SCHEDULABLE_REPORT_KEYS.map((key) => ({ value: key, label: key }))}
          />
          <SelectField
            id={`${id}-cadence`}
            label={t("reports.schedule.cadence")}
            value={cadence}
            onChange={(event) => setCadence(event.target.value as ReportScheduleCadence)}
            options={CADENCES.map((value) => ({ value, label: t(`reports.schedule.cadence.${value}`) }))}
          />
          <SelectField
            id={`${id}-format`}
            label={t("reports.schedule.format")}
            value={format}
            onChange={(event) => setFormat(event.target.value as ReportExportFormat)}
            options={FORMATS.map((value) => ({ value, label: t(`reports.export.${value}`) }))}
          />
        </div>
        <div>
          <label className="qms-label" htmlFor={`${id}-recipients`}>
            {t("reports.schedule.recipients")}
          </label>
          <textarea
            className="qms-input"
            id={`${id}-recipients`}
            rows={3}
            value={recipientsText}
            onChange={(event) => setRecipientsText(event.target.value)}
            placeholder="ops@example.com"
          />
          <span className="qms-muted">{t("reports.schedule.recipientsHint")}</span>
        </div>
        <Button type="button" onClick={create} disabled={busy || parseRecipients(recipientsText).length === 0}>
          {busy ? t("reports.schedule.creating") : t("reports.schedule.create")}
        </Button>
        {error && <ErrorAlert>{error}</ErrorAlert>}
      </div>
    </Card>
  );
}

function ScheduleRow({ schedule, onChanged }: { schedule: ReportSchedule; onChanged: () => void }) {
  const { t } = useI18n();
  const { client } = useApi();
  const [showDeliveries, setShowDeliveries] = useState(false);
  const deliveries = useList<ReportScheduleDelivery>(
    client && showDeliveries ? () => client.reports.schedules.deliveries(schedule.id) : null,
    [client, showDeliveries, schedule.id],
  );
  const { busy, error, run } = useSubmit();

  async function toggleEnabled() {
    if (!client) return;
    const ok = await run(() =>
      client.reports.schedules.update(schedule.id, {
        report_key: schedule.report_key,
        cadence: schedule.cadence,
        format: schedule.format,
        recipients: schedule.recipients,
        filter: schedule.filter,
        enabled: !schedule.enabled,
      }),
    );
    if (ok) onChanged();
  }

  async function remove() {
    if (!client || !window.confirm(t("reports.schedule.confirmDelete"))) return;
    const ok = await run(() => client.reports.schedules.remove(schedule.id));
    if (ok) onChanged();
  }

  function deliveryLine(delivery: ReportScheduleDelivery): string {
    if (delivery.status === "sent") return t("reports.schedule.deliveries.sent", { recipient: delivery.recipient ?? "" });
    return delivery.recipient
      ? t("reports.schedule.deliveries.failed", { recipient: delivery.recipient, error: delivery.error ?? "" })
      : t("reports.schedule.deliveries.generationFailed", { error: delivery.error ?? "" });
  }

  return (
    <li>
      <div className="qms-stack qms-grow">
        <div className="qms-row">
          <strong>{schedule.report_key}</strong>
          <span className={`qms-badge qms-badge--${schedule.enabled ? "up" : "not_configured"}`}>
            {t(schedule.enabled ? "reports.schedule.enabled" : "reports.schedule.disabled")}
          </span>
        </div>
        <span className="qms-muted">
          {t(`reports.schedule.cadence.${schedule.cadence}`)} · {schedule.format.toUpperCase()} · {t("reports.schedule.recipientCount", { count: schedule.recipients.length })}
        </span>
        <span className="qms-muted">
          {t("reports.schedule.nextRun")}: {new Date(schedule.next_run_at).toLocaleString()} · {t("reports.schedule.lastRun")}:{" "}
          {schedule.last_run_at ? new Date(schedule.last_run_at).toLocaleString() : t("reports.schedule.never")}
        </span>
        <div className="qms-row">
          <Button variant="secondary" type="button" onClick={toggleEnabled} disabled={busy}>
            {t(schedule.enabled ? "reports.schedule.disable" : "reports.schedule.enable")}
          </Button>
          <Button variant="secondary" type="button" onClick={remove} disabled={busy}>
            {t("reports.schedule.delete")}
          </Button>
          <Button variant="secondary" type="button" onClick={() => setShowDeliveries((v) => !v)}>
            {t(showDeliveries ? "reports.schedule.deliveries.hide" : "reports.schedule.deliveries.show")}
          </Button>
        </div>
        {error && <ErrorAlert>{error}</ErrorAlert>}
        {showDeliveries && (
          <div className="qms-stack">
            {deliveries.error !== null && <ErrorAlert>{describeError(t, deliveries.error)}</ErrorAlert>}
            {deliveries.items?.length === 0 && <span className="qms-muted">{t("reports.schedule.deliveries.empty")}</span>}
            {deliveries.items && deliveries.items.length > 0 && (
              <ul className="qms-list">
                {deliveries.items.map((delivery) => (
                  <li key={delivery.id}>
                    <span className={delivery.status === "sent" ? "qms-muted" : "qms-warning"}>{deliveryLine(delivery)}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </li>
  );
}
