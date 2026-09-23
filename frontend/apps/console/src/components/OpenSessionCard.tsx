"use client";

import type { CounterSession, SessionCounterOption } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert } from "@qms/ui";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { describeError, localisedName } from "../lib/console-support";
import { useApi } from "../lib/runtime";

/**
 * Opening a session (SRS §11.1): pick one of the counters the API says this agent may occupy, then which of its Services to
 * serve this session, all of them unless changed (FR-AGT-001, FR-AGT-003). The API decides; a counter that turns out to be
 * taken is refused there and the list is read again.
 */
export function OpenSessionCard({ onOpened }: { onOpened: (session: CounterSession) => void }) {
  const { t, language } = useI18n();
  const { client } = useApi();
  const [options, setOptions] = useState<SessionCounterOption[] | null>(null);
  const [counterId, setCounterId] = useState("");
  const [serviceIds, setServiceIds] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!client) return;
    try {
      setOptions((await client.sessions.options()).items);
    } catch (cause) {
      setError(describeError(t, cause));
    }
  }, [client, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const chosen = options?.find((o) => o.counter.id === counterId) ?? null;

  function choose(option: SessionCounterOption) {
    setCounterId(option.counter.id);
    setServiceIds(new Set(option.services.map((s) => s.id)));
  }

  function toggle(id: string) {
    const next = new Set(serviceIds);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setServiceIds(next);
  }

  async function open(event: FormEvent) {
    event.preventDefault();
    if (!client || !chosen) return;
    setBusy(true);
    setError(null);
    try {
      const all = chosen.services.every((s) => serviceIds.has(s.id));
      onOpened(await client.sessions.open({ counter_id: chosen.counter.id, ...(all ? {} : { service_ids: [...serviceIds] }) }));
    } catch (cause) {
      setError(describeError(t, cause));
      void load();
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <h2 className="text-lg font-semibold text-fg">{t("console.open.title")}</h2>
      {error !== null && <ErrorAlert>{error}</ErrorAlert>}
      {options === null && error === null && <p className="text-fg-muted">{t("common.loading")}</p>}
      {options?.length === 0 && <p className="text-fg-muted">{t("console.open.none")}</p>}
      {options && options.length > 0 && (
        <form className="flex flex-col gap-4" onSubmit={open}>
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 font-semibold text-fg">{t("console.open.counter")}</legend>
            {options.map((option) => (
              <label key={option.counter.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border px-3 py-2">
                <span className="flex items-center gap-2 text-sm text-fg">
                  <input
                    type="radio"
                    name="console-counter"
                    value={option.counter.id}
                    checked={counterId === option.counter.id}
                    disabled={option.occupied}
                    onChange={() => choose(option)}
                  />{" "}
                  {t("console.open.counterOption", { label: option.counter.label, zone: option.counter.zone_name })}
                </span>
                {option.occupied && <span className="text-warn">{t("console.open.occupied")}</span>}
              </label>
            ))}
          </fieldset>
          {chosen && (
            <fieldset className="flex flex-col gap-2">
              <legend className="mb-1 font-semibold text-fg">{t("console.open.services")}</legend>
              {chosen.services.map((service) => (
                <label key={service.id} className="flex items-center gap-2 text-sm text-fg">
                  <input type="checkbox" checked={serviceIds.has(service.id)} onChange={() => toggle(service.id)} />{" "}
                  {localisedName(service.name_i18n, language)}
                  {service.preference_weight > 1 && <span className="text-fg-muted"> · {t("console.open.fallback")}</span>}
                </label>
              ))}
            </fieldset>
          )}
          <div>
            <Button type="submit" disabled={!chosen || serviceIds.size === 0 || busy}>
              {t(busy ? "console.open.opening" : "console.open.submit")}
            </Button>
          </div>
        </form>
      )}
    </Card>
  );
}
