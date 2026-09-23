"use client";

import { ApiRequestError, type Items } from "@qms/api-client";
import type { I18n } from "@qms/i18n";
import { useI18n } from "@qms/i18n/react";
import { SHIPPED_LANGUAGES } from "@qms/i18n";
import { ConfirmDialog, StatusBadge } from "@qms/ui";
import { useCallback, useEffect, useState, type ReactNode } from "react";

/** Field names of the service catalogue; their labels live under `catalogue.fields`, the rest under `sites.fields`. */
const CATALOGUE_FIELDS = new Set([
  "name_i18n",
  "label_i18n",
  "token_prefix",
  "display_order",
  "expected_minutes",
  "sla_wait_minutes",
  "channels",
  "icon",
  "visitor_identifier",
  "booking_mode",
  "parallel_serving",
  "parallel_limit",
  "preference_weight",
  "counter_id",
  "code",
  "user_id",
  "prefix_source",
  "fixed_prefix",
  "sequence_start",
  "padding",
  "reset_boundary",
  "reset_time",
  "separator",
  "headstart_minutes",
  "max_wait_minutes",
  "token_prefix_override",
  "strategy",
  "priority_class_id",
  "max_minutes",
  "break_type_id",
  "grain",
]);

/** A localised sentence for a failed call; a validation failure also names the fields to check (SRS §20.3). */
export function describeError(t: I18n["t"], cause: unknown): string {
  const code = cause instanceof ApiRequestError ? cause.code : "network_error";
  const message = t(`errors.${code}`);
  if (!(cause instanceof ApiRequestError) || code !== "validation_failed") return message;
  const fields = cause.body?.details?.fields;
  if (!Array.isArray(fields)) return message;
  const names = [...new Set(fields.map((f: { field?: string }) => f.field).filter((f): f is string => Boolean(f)))].map((f) =>
    t(CATALOGUE_FIELDS.has(f) ? `catalogue.fields.${f}` : `sites.fields.${f}`),
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

interface ConfirmRequest {
  title: ReactNode;
  description?: ReactNode;
  /** Defaults to the generic "Confirm" (`common.confirm`); pass the specific action's own label to keep it. */
  confirmLabel?: string;
  danger?: boolean;
  onConfirm: () => void;
}

/**
 * A shared `ConfirmDialog` (ticket 63): replaces the inline expand/collapse "are you sure" panels and
 * `window.confirm` calls scattered across admin with one accessible, focus-trapped dialog. `ask` opens it;
 * `dialog` is rendered once near the top of the component tree that owns the destructive action.
 */
export function useConfirmDialog() {
  const { t } = useI18n();
  const [request, setRequest] = useState<ConfirmRequest | null>(null);

  const ask = useCallback((next: ConfirmRequest) => setRequest(next), []);
  const cancel = useCallback(() => setRequest(null), []);

  const dialog = (
    <ConfirmDialog
      open={request !== null}
      title={request?.title ?? ""}
      description={request?.description}
      danger={request?.danger}
      confirmLabel={request?.confirmLabel ?? t("common.confirm")}
      cancelLabel={t("common.cancel")}
      onConfirm={() => {
        const current = request;
        setRequest(null);
        current?.onConfirm();
      }}
      onCancel={cancel}
    />
  );

  return { ask, dialog };
}

export function ActiveBadge({ active }: { active: boolean }) {
  const { t } = useI18n();
  return <StatusBadge status={active ? "up" : "not_configured"}>{t(active ? "admin.status.active" : "admin.status.inactive")}</StatusBadge>;
}

export interface FormValidation<V> {
  /** The message to show through a field's `error` prop, or `undefined` while it is valid. */
  message: (field: string) => string | undefined;
  /** Re-checks one field against the current values (ticket 66: "validate on blur"). */
  validateField: (field: string, values: V) => void;
  /** Re-checks every field (ticket 66: "validate on submit"). Blocks and focuses the first invalid field's DOM
   * `id` (from the `ids` map passed to {@link useFormValidation}) when anything is invalid; returns whether the
   * form was valid, so the caller can decide whether to go on and submit. */
  validateAll: (values: V) => boolean;
  /** Drops every error, e.g. after a successful submit resets the form. */
  clear: () => void;
}

/**
 * Client-side field validation that mirrors the server's own rules (ticket 66, SRS §27.5). `rules` maps a field
 * name to a pure function of the form's current values returning an i18n error key (see `@qms/ui`'s
 * `validators.ts`) or `undefined`; `ids` maps that same field name to the DOM `id` of the input that renders it, so
 * the first invalid field can be focused. The server stays authoritative — this only ever blocks a submit the
 * server would have refused anyway.
 */
export function useFormValidation<V>(rules: Record<string, (values: V) => string | undefined>, ids: Record<string, string>): FormValidation<V> {
  const { t } = useI18n();
  const [errors, setErrors] = useState<Record<string, string>>({});

  const validateField = useCallback(
    (field: string, values: V) => {
      const rule = rules[field];
      if (!rule) return;
      const key = rule(values);
      setErrors((prev) => {
        if (!key) {
          if (!(field in prev)) return prev;
          const next = { ...prev };
          delete next[field];
          return next;
        }
        return prev[field] === key ? prev : { ...prev, [field]: key };
      });
    },
    [rules],
  );

  const validateAll = useCallback(
    (values: V): boolean => {
      const next: Record<string, string> = {};
      let firstInvalidField: string | null = null;
      for (const field of Object.keys(rules)) {
        const key = rules[field]!(values);
        if (key) {
          next[field] = key;
          if (firstInvalidField === null) firstInvalidField = field;
        }
      }
      setErrors(next);
      if (firstInvalidField) {
        document.getElementById(ids[firstInvalidField] ?? "")?.focus();
        return false;
      }
      return true;
    },
    [rules, ids],
  );

  return {
    message: (field) => (errors[field] ? t(errors[field]!) : undefined),
    validateField,
    validateAll,
    clear: () => setErrors({}),
  };
}

export function languageName(t: I18n["t"], code: string): string {
  return SHIPPED_LANGUAGES.includes(code) ? t(`languages.${code}`) : code;
}

/** A name in the reader's language, falling back to the site default, never to a raw key or nothing (FR-I18N-011). */
export function localisedName(names: Record<string, string>, language: string, defaultLanguage: string): string {
  return names[language] ?? names[defaultLanguage] ?? Object.values(names)[0] ?? "";
}
