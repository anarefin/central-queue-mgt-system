"use client";

import {
  BOOKING_MODES,
  CHANNELS,
  VISITOR_IDENTIFIERS,
  type BookingMode,
  type Channel,
  type ServiceEntry,
  type ServiceInput,
  type Site,
  type VisitorIdentifier,
} from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert, SelectField, TextField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { useSubmit } from "../lib/admin-support";
import { nameValues, TranslatedNameFields } from "./TranslatedNameFields";

interface ServiceFormProps {
  site: Site;
  initial?: ServiceEntry;
  submitLabel: string;
  onSubmit: (input: ServiceInput) => Promise<unknown>;
  onDone: () => void;
  onCancel?: () => void;
}

/**
 * A service (FR-CFG-010): names per language, token prefix, expected handling and SLA wait minutes, enabled channels,
 * kiosk icon and display order (FR-CFG-012), visitor identifier (FR-CFG-013), appointment / walk-in mode (FR-CFG-014) and whether a counter
 * serves several visitors of it at once, with the most it may have in progress (FR-AGT-010, FR-AGT-011).
 */
export function ServiceForm({ site, initial, submitLabel, onSubmit, onDone, onCancel }: ServiceFormProps) {
  const { t } = useI18n();
  const id = useId();
  const [names, setNames] = useState(nameValues(site.enabled_languages, initial?.name_i18n));
  const [prefix, setPrefix] = useState(initial?.token_prefix ?? "");
  const [expected, setExpected] = useState(String(initial?.expected_minutes ?? ""));
  const [sla, setSla] = useState(String(initial?.sla_wait_minutes ?? ""));
  const [channels, setChannels] = useState<Channel[]>(initial?.channels ?? [...CHANNELS]);
  const [icon, setIcon] = useState(initial?.icon ?? "");
  const [order, setOrder] = useState(String(initial?.display_order ?? 0));
  const [identifier, setIdentifier] = useState<VisitorIdentifier>(initial?.visitor_identifier ?? "not_required");
  const [booking, setBooking] = useState<BookingMode>(initial?.booking_mode ?? "both");
  const [parallel, setParallel] = useState(initial?.parallel_serving ?? false);
  const [parallelLimit, setParallelLimit] = useState(String(initial?.parallel_limit && initial.parallel_limit > 1 ? initial.parallel_limit : 2));
  const { busy, error, run } = useSubmit();

  const toggle = (channel: Channel) =>
    setChannels((current) => (current.includes(channel) ? current.filter((c) => c !== channel) : CHANNELS.filter((c) => c === channel || current.includes(c))));

  async function submit(event: FormEvent) {
    event.preventDefault();
    const input: ServiceInput = {
      name_i18n: names,
      token_prefix: prefix,
      expected_minutes: Number(expected),
      sla_wait_minutes: Number(sla),
      channels,
      icon,
      display_order: Number(order),
      visitor_identifier: identifier,
      booking_mode: booking,
      parallel_serving: parallel,
      // Only a parallel Service has a maximum to send; otherwise the one kept for it stays as it is.
      ...(parallel ? { parallel_limit: Number(parallelLimit) } : {}),
    };
    if (await run(() => onSubmit(input))) onDone();
  }

  return (
    <form className="qms-stack" onSubmit={submit}>
      <TranslatedNameFields
        id={`${id}-name`}
        label={t("catalogue.fields.name_i18n")}
        languages={site.enabled_languages}
        defaultLanguage={site.default_language}
        value={names}
        onChange={setNames}
      />
      <TextField id={`${id}-prefix`} label={t("catalogue.fields.token_prefix")} value={prefix} onChange={(e) => setPrefix(e.target.value)} />
      <TextField
        id={`${id}-expected`}
        type="number"
        label={t("catalogue.fields.expected_minutes")}
        value={expected}
        onChange={(e) => setExpected(e.target.value)}
      />
      <TextField id={`${id}-sla`} type="number" label={t("catalogue.fields.sla_wait_minutes")} value={sla} onChange={(e) => setSla(e.target.value)} />
      <fieldset className="qms-stack">
        <legend className="qms-label">{t("catalogue.fields.channels")}</legend>
        {CHANNELS.map((channel) => (
          <label key={channel} className="qms-row">
            <input type="checkbox" checked={channels.includes(channel)} onChange={() => toggle(channel)} />
            {t(`catalogue.channel.${channel}`)}
          </label>
        ))}
      </fieldset>
      <TextField id={`${id}-icon`} label={t("catalogue.fields.icon")} value={icon} onChange={(e) => setIcon(e.target.value)} />
      <TextField id={`${id}-order`} type="number" label={t("catalogue.fields.display_order")} value={order} onChange={(e) => setOrder(e.target.value)} />
      <SelectField
        id={`${id}-identifier`}
        label={t("catalogue.fields.visitor_identifier")}
        value={identifier}
        onChange={(e) => setIdentifier(e.target.value as VisitorIdentifier)}
        options={VISITOR_IDENTIFIERS.map((value) => ({ value, label: t(`catalogue.visitorIdentifier.${value}`) }))}
      />
      <SelectField
        id={`${id}-booking`}
        label={t("catalogue.fields.booking_mode")}
        value={booking}
        onChange={(e) => setBooking(e.target.value as BookingMode)}
        options={BOOKING_MODES.map((value) => ({ value, label: t(`catalogue.bookingMode.${value}`) }))}
      />
      <label className="qms-row">
        <input type="checkbox" checked={parallel} onChange={(e) => setParallel(e.target.checked)} />
        {t("catalogue.fields.parallel_serving")}
      </label>
      {parallel && (
        <TextField
          id={`${id}-parallel-limit`}
          type="number"
          label={t("catalogue.fields.parallel_limit")}
          value={parallelLimit}
          onChange={(e) => setParallelLimit(e.target.value)}
        />
      )}
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <div className="qms-row">
        <Button type="submit" disabled={busy}>
          {busy ? t("admin.action.saving") : submitLabel}
        </Button>
        {onCancel && (
          <Button variant="secondary" type="button" onClick={onCancel}>
            {t("admin.action.cancel")}
          </Button>
        )}
      </div>
    </form>
  );
}
