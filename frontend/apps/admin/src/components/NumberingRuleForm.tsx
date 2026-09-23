"use client";

import { PREFIX_SOURCES, RESET_BOUNDARIES, type NumberingRule, type NumberingRuleInput, type PrefixSource, type ResetBoundary } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert, SelectField, TextField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { useSubmit } from "../lib/admin-support";

interface NumberingRuleFormProps {
  initial?: NumberingRule;
  onSubmit: (input: NumberingRuleInput) => Promise<unknown>;
  onDone: () => void;
  onCancel: () => void;
}

/** The parameters of a numbering rule (FR-CFG-018), starting from the SRS defaults when the scope has no rule yet. */
export function NumberingRuleForm({ initial, onSubmit, onDone, onCancel }: NumberingRuleFormProps) {
  const { t } = useI18n();
  const id = useId();
  const [source, setSource] = useState<PrefixSource>(initial?.prefix_source ?? "service_group");
  const [fixed, setFixed] = useState(initial?.fixed_prefix ?? "");
  const [start, setStart] = useState(String(initial?.sequence_start ?? 1));
  const [padding, setPadding] = useState(String(initial?.padding ?? 3));
  const [boundary, setBoundary] = useState<ResetBoundary>(initial?.reset_boundary ?? "daily");
  const [time, setTime] = useState(initial?.reset_time ?? "00:00");
  const [separator, setSeparator] = useState(initial?.separator ?? "-");
  const { busy, error, run } = useSubmit();

  async function submit(event: FormEvent) {
    event.preventDefault();
    const input: NumberingRuleInput = {
      prefix_source: source,
      ...(source === "fixed" ? { fixed_prefix: fixed } : {}),
      sequence_start: Number(start),
      padding: Number(padding),
      reset_boundary: boundary,
      reset_time: time,
      separator,
    };
    if (await run(() => onSubmit(input))) onDone();
  }

  return (
    <form className="flex flex-col gap-4" onSubmit={submit}>
      <SelectField
        id={`${id}-source`}
        label={t("catalogue.fields.prefix_source")}
        value={source}
        onChange={(e) => setSource(e.target.value as PrefixSource)}
        options={PREFIX_SOURCES.map((value) => ({ value, label: t(`numbering.source.${value}`) }))}
      />
      {source === "fixed" && (
        <TextField id={`${id}-fixed`} label={t("catalogue.fields.fixed_prefix")} value={fixed} onChange={(e) => setFixed(e.target.value)} />
      )}
      <TextField id={`${id}-separator`} label={t("catalogue.fields.separator")} value={separator} onChange={(e) => setSeparator(e.target.value)} />
      <TextField
        id={`${id}-padding`}
        type="number"
        min={0}
        max={6}
        label={t("catalogue.fields.padding")}
        value={padding}
        onChange={(e) => setPadding(e.target.value)}
      />
      <TextField
        id={`${id}-start`}
        type="number"
        min={0}
        label={t("catalogue.fields.sequence_start")}
        value={start}
        onChange={(e) => setStart(e.target.value)}
      />
      <SelectField
        id={`${id}-boundary`}
        label={t("catalogue.fields.reset_boundary")}
        value={boundary}
        onChange={(e) => setBoundary(e.target.value as ResetBoundary)}
        options={RESET_BOUNDARIES.map((value) => ({ value, label: t(`numbering.boundary.${value}`) }))}
      />
      {boundary !== "never" && (
        <TextField id={`${id}-time`} type="time" label={t("catalogue.fields.reset_time")} value={time} onChange={(e) => setTime(e.target.value)} />
      )}
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Button type="submit" disabled={busy}>
          {busy ? t("admin.action.saving") : t("admin.action.save")}
        </Button>
        <Button variant="secondary" type="button" onClick={onCancel}>
          {t("admin.action.cancel")}
        </Button>
      </div>
    </form>
  );
}
