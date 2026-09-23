"use client";

import type { BreakType } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert, SelectField, SidePanel } from "@qms/ui";
import { useEffect, useState, type FormEvent } from "react";
import { describeError, localisedName } from "../lib/console-support";
import { useApi } from "../lib/runtime";

interface Props {
  busy: boolean;
  onSubmit: (breakTypeId: string) => void;
  onCancel: () => void;
}

/**
 * Start a break (F9, SRS §11.2, FR-AGT-020, FR-AGT-021): pick one of the break types an admin has set up, each with its longest
 * duration if it has one. Only active types are offered. The API refuses a break while a ticket is called or serving, and again on
 * the type, so this panel only offers what is likely to work. Esc closes it without starting anything.
 */
export function BreakPanel({ busy, onSubmit, onCancel }: Props) {
  const { t, language, formatNumber } = useI18n();
  const { client } = useApi();
  const [types, setTypes] = useState<BreakType[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [typeId, setTypeId] = useState("");

  useEffect(() => {
    if (!client) return;
    let current = true;
    client.breaks
      .types()
      .then((loaded) => current && setTypes(loaded.items.filter((type) => type.active)))
      .catch((cause) => current && setError(describeError(t, cause)));
    return () => {
      current = false;
    };
  }, [client, t]);

  useEffect(() => {
    if (types && types.length > 0) document.getElementById("break-type")?.focus();
  }, [types]);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (typeId !== "") onSubmit(typeId);
  }

  return (
    <SidePanel label={t("console.break.title")} onClose={onCancel}>
      <form className="flex flex-col gap-4" onSubmit={submit}>
        <h3 className="text-lg font-semibold text-fg">{t("console.break.title")}</h3>
        {error !== null && <ErrorAlert>{error}</ErrorAlert>}
        {types === null && error === null && <p className="text-fg-muted">{t("common.loading")}</p>}
        {types?.length === 0 && <p className="text-fg-muted">{t("console.break.none")}</p>}
        {types && types.length > 0 && (
          <SelectField
            id="break-type"
            label={t("console.break.type")}
            value={typeId}
            onChange={(event) => setTypeId(event.target.value)}
            options={[
              { value: "", label: t("console.break.choose") },
              ...types.map((type) => {
                const name = localisedName(type.name_i18n, language);
                return { value: type.id, label: type.max_minutes === null ? name : t("console.break.option", { name, minutes: formatNumber(type.max_minutes) }) };
              }),
            ]}
          />
        )}
        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" disabled={typeId === "" || busy}>
            {t("console.break.start")}
          </Button>
          <Button type="button" variant="secondary" onClick={onCancel}>
            {t("console.break.cancel")}
          </Button>
        </div>
      </form>
    </SidePanel>
  );
}
