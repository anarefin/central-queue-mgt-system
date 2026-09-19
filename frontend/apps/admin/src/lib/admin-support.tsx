"use client";

import { ApiRequestError, type Items } from "@qms/api-client";
import type { I18n } from "@qms/i18n";
import { useI18n } from "@qms/i18n/react";
import { SHIPPED_LANGUAGES } from "@qms/i18n";
import { StatusBadge } from "@qms/ui";
import { useCallback, useEffect, useState } from "react";

/** A localised sentence for a failed call; a validation failure also names the fields to check (SRS §20.3). */
export function describeError(t: I18n["t"], cause: unknown): string {
  const code = cause instanceof ApiRequestError ? cause.code : "network_error";
  const message = t(`errors.${code}`);
  if (!(cause instanceof ApiRequestError) || code !== "validation_failed") return message;
  const fields = cause.body?.details?.fields;
  if (!Array.isArray(fields)) return message;
  const names = [...new Set(fields.map((f: { field?: string }) => f.field).filter((f): f is string => Boolean(f)))].map((f) =>
    t(`sites.fields.${f}`),
  );
  return names.length > 0 ? `${message} ${t("sites.invalidFields", { fields: names.join(", ") })}` : message;
}

/** Loads a list through the api-client; `load` is null until the client is ready. */
export function useList<T>(load: (() => Promise<Items<T>>) | null, deps: readonly unknown[]) {
  const [items, setItems] = useState<T[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    if (!load) return;
    let cancelled = false;
    setError(null);
    load().then(
      (result) => !cancelled && setItems(result.items),
      (cause: unknown) => !cancelled && setError(cause),
    );
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  return { items, error, reload };
}

/** Runs one write at a time and keeps the busy flag and the localised error for the form or card that started it. */
export function useSubmit() {
  const { t } = useI18n();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = useCallback(
    async (action: () => Promise<unknown>): Promise<boolean> => {
      setBusy(true);
      setError(null);
      try {
        await action();
        return true;
      } catch (cause) {
        setError(describeError(t, cause));
        return false;
      } finally {
        setBusy(false);
      }
    },
    [t],
  );
  return { busy, error, run, clearError: () => setError(null) };
}

export function ActiveBadge({ active }: { active: boolean }) {
  const { t } = useI18n();
  return <StatusBadge status={active ? "up" : "not_configured"}>{t(active ? "admin.status.active" : "admin.status.inactive")}</StatusBadge>;
}

export function languageName(t: I18n["t"], code: string): string {
  return SHIPPED_LANGUAGES.includes(code) ? t(`languages.${code}`) : code;
}
