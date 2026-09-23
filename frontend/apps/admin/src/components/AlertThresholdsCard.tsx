"use client";

import type { AlertThreshold, AlertThresholdInput, Site } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField, TextField } from "@qms/ui";
import { useEffect, useState } from "react";
import { describeError, localisedName, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

const FIELDS = [
  "queue_length_max",
  "longest_wait_minutes_max",
  "idle_counters_with_queue_max",
  "no_show_rate_percent_max",
  "device_offline_minutes_max",
  "group_window_minutes",
  "escalation_delay_minutes",
] as const;

type Field = (typeof FIELDS)[number];

function blank(): Record<Field, string> {
  return { queue_length_max: "", longest_wait_minutes_max: "", idle_counters_with_queue_max: "", no_show_rate_percent_max: "", device_offline_minutes_max: "", group_window_minutes: "", escalation_delay_minutes: "" };
}

function fromThreshold(t: AlertThreshold): Record<Field, string> {
  const values = blank();
  for (const field of FIELDS) {
    const value = t[field];
    values[field] = value === null ? "" : String(value);
  }
  return values;
}

function toInput(values: Record<Field, string>): AlertThresholdInput {
  const parsed: Partial<Record<Field, number | null>> = {};
  for (const field of FIELDS) {
    const raw = values[field].trim();
    parsed[field] = raw === "" ? null : Number(raw);
  }
  return parsed as AlertThresholdInput;
}

/**
 * A Service's own alert thresholds (SRS §15.4, FR-MON-020, ticket 47): queue length, longest wait, counters idle
 * with a queue waiting, no-show rate and device offline duration, plus this Service's own override of the grouping
 * window (FR-MON-023) and escalation delay (FR-MON-021). A field left blank leaves that metric unmonitored.
 */
export function AlertThresholdsCard({ site }: { site: Site }) {
  const { t, language } = useI18n();
  const { client } = useApi();
  const [serviceId, setServiceId] = useState("");
  const services = useList(client ? () => client.sites.services(site.id) : null, [client, site.id]);
  const [values, setValues] = useState<Record<Field, string> | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);
  const { busy, error, run } = useSubmit();
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    setSaved(false);
    if (!client || !serviceId) {
      setValues(null);
      return;
    }
    let cancelled = false;
    client.alerts.thresholds(serviceId).then(
      (result) => !cancelled && setValues(fromThreshold(result)),
      (cause) => !cancelled && setLoadError(cause),
    );
    return () => {
      cancelled = true;
    };
  }, [client, serviceId]);

  async function save() {
    if (!client || !serviceId || !values) return;
    setSaved(false);
    const ok = await run(() => client.alerts.setThresholds(serviceId, toInput(values)));
    if (ok) setSaved(true);
  }

  return (
    <Card>
      <h2 className="font-semibold text-fg">{t("alerts.thresholds.title")}</h2>
      <p className="text-fg-muted">{t("alerts.thresholds.intro")}</p>
      {services.items && services.items.length > 0 && (
        <SelectField
          id="alert-threshold-service"
          label={t("alerts.thresholds.service")}
          value={serviceId}
          onChange={(event) => setServiceId(event.target.value)}
          options={[
            { value: "", label: t("dashboard.filter.all") },
            ...services.items.map((s) => ({ value: s.id, label: localisedName(s.name_i18n, language, site.default_language) })),
          ]}
        />
      )}
      {(services.error ?? loadError) != null && <ErrorAlert>{describeError(t, services.error ?? loadError)}</ErrorAlert>}
      {serviceId && values && (
        <div className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            {FIELDS.map((field) => (
              <TextField
                key={field}
                id={`alert-threshold-${field}`}
                label={t(`alerts.thresholds.field.${field}`)}
                value={values[field]}
                onChange={(event) => setValues((v) => (v ? { ...v, [field]: event.target.value } : v))}
              />
            ))}
          </div>
          <Button type="button" onClick={save} disabled={busy}>
            {t("alerts.thresholds.save")}
          </Button>
          {saved && <p className="text-fg-muted">{t("alerts.thresholds.saved")}</p>}
          {error && <ErrorAlert>{error}</ErrorAlert>}
        </div>
      )}
    </Card>
  );
}
