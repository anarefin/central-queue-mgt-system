"use client";

import { SHIPPED_LANGUAGES, SHIPPED_PACKS } from "@qms/i18n";
import { ENTITY_KEYS, useI18n, useLabels } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, Loading, requiredText, TextField } from "@qms/ui";
import { useEffect, useId, useState } from "react";
import { languageName, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

const MAX_VALUE = 60;

/** Resolved overrides, per key then per language (only the keys/languages that actually have a row). */
type Overrides = Record<string, Record<string, string>>;

/** The pack's own default noun for one key/language, independent of the admin's own active UI language. */
function packDefault(key: string, lang: string): string {
  return SHIPPED_PACKS[lang]?.messages[key] ?? SHIPPED_PACKS.en?.messages[key] ?? key;
}

/**
 * Terminology remapping admin screen (SRS §3.2, ticket 69): a table of the seven `entity.*` keys by enabled
 * language, each cell showing the pack's own default noun, the current override if one exists, an inline edit and a
 * reset back to the default. Saving calls `PUT /labels/{key}`; resetting calls `DELETE /labels/{key}?lang=`, both
 * audited server-side and both pushed to every device as `config.changed` (kiosk/display update without a reload).
 */
export function TerminologyCard() {
  const { t } = useI18n();
  const { client } = useApi();
  const [overrides, setOverrides] = useState<Overrides | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [editing, setEditing] = useState<{ key: string; lang: string } | null>(null);

  async function load() {
    if (!client) return;
    try {
      const perLanguage = await Promise.all(SHIPPED_LANGUAGES.map(async (lang) => [lang, await client.labels.get(lang)] as const));
      const next: Overrides = {};
      for (const key of ENTITY_KEYS) next[`entity.${key}`] = {};
      for (const [lang, resolved] of perLanguage) {
        for (const key of ENTITY_KEYS) {
          const wireKey = `entity.${key}`;
          const value = resolved[wireKey];
          // A resolved value that differs from the pack default is an override; one that matches it might be an
          // override set to the same text as the default, but that distinction has no visible effect either way.
          if (value !== undefined && value !== packDefault(wireKey, lang)) next[wireKey]![lang] = value;
        }
      }
      setOverrides(next);
      setLoadError(null);
    } catch {
      setLoadError(t("errors.network_error"));
    }
  }

  useEffect(() => {
    void load();
  }, [client]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <Card>
      <h2 className="font-semibold text-fg">{t("admin.labels.title")}</h2>
      <p className="text-fg-muted">{t("admin.labels.intro")}</p>
      {loadError && <ErrorAlert>{loadError}</ErrorAlert>}
      {overrides === null ? (
        <Loading>{t("admin.labels.loading")}</Loading>
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-sm">
              <thead>
                <tr className="border-b border-border text-start">
                  <th scope="col" className="py-2 pe-4 font-semibold text-fg">{t("admin.labels.col.key")}</th>
                  {SHIPPED_LANGUAGES.map((lang) => (
                    <th key={lang} scope="col" className="py-2 pe-4 font-semibold text-fg">
                      {languageName(t, lang)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {ENTITY_KEYS.map((entityKey) => {
                  const wireKey = `entity.${entityKey}`;
                  return (
                    <tr key={wireKey} className="border-b border-border">
                      <td className="py-2 pe-4 align-top font-medium text-fg">{packDefault(wireKey, "en")}</td>
                      {SHIPPED_LANGUAGES.map((lang) => (
                        <td key={lang} className="py-2 pe-4 align-top">
                          {editing && editing.key === wireKey && editing.lang === lang ? (
                            <LabelCellEditor
                              wireKey={wireKey}
                              lang={lang}
                              initial={overrides[wireKey]?.[lang] ?? packDefault(wireKey, lang)}
                              onDone={async () => {
                                setEditing(null);
                                await load();
                              }}
                              onCancel={() => setEditing(null)}
                            />
                          ) : (
                            <LabelCellView
                              value={overrides[wireKey]?.[lang]}
                              defaultValue={packDefault(wireKey, lang)}
                              onEdit={() => setEditing({ key: wireKey, lang })}
                              onReset={
                                overrides[wireKey]?.[lang] !== undefined
                                  ? async () => {
                                      await client?.labels.reset(wireKey, lang);
                                      await load();
                                    }
                                  : undefined
                              }
                            />
                          )}
                        </td>
                      ))}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <LabelsPreview />
        </>
      )}
    </Card>
  );
}

function LabelCellView({
  value,
  defaultValue,
  onEdit,
  onReset,
}: {
  value: string | undefined;
  defaultValue: string;
  onEdit: () => void;
  onReset?: () => Promise<void>;
}) {
  const { t } = useI18n();
  const [resetting, setResetting] = useState(false);
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="text-fg">{value ?? defaultValue}</span>
      {value === undefined && <span className="text-xs text-fg-muted">{t("admin.labels.default")}</span>}
      <Button type="button" size="sm" variant="secondary" onClick={onEdit}>
        {t("admin.action.edit")}
      </Button>
      {onReset && (
        <Button
          type="button"
          size="sm"
          variant="ghost"
          disabled={resetting}
          onClick={async () => {
            setResetting(true);
            try {
              await onReset();
            } finally {
              setResetting(false);
            }
          }}
        >
          {t("admin.labels.reset")}
        </Button>
      )}
    </div>
  );
}

function LabelCellEditor({
  wireKey,
  lang,
  initial,
  onDone,
  onCancel,
}: {
  wireKey: string;
  lang: string;
  initial: string;
  onDone: () => Promise<void>;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const [value, setValue] = useState(initial);
  const [fieldError, setFieldError] = useState<string | undefined>(undefined);
  const { busy, error, run } = useSubmit();

  async function save() {
    const problem = requiredText(value, MAX_VALUE);
    if (problem) {
      setFieldError(t(problem));
      return;
    }
    setFieldError(undefined);
    const ok = await run(() => client!.labels.update(wireKey, { lang, value: value.trim() }));
    if (ok) await onDone();
  }

  return (
    <form
      className="flex flex-col gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        void save();
      }}
    >
      <TextField id={`${id}-value`} label={t("admin.labels.valueLabel")} value={value} maxLength={MAX_VALUE} onChange={(e) => setValue(e.target.value)} error={fieldError} />
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={busy}>
          {t("admin.action.save")}
        </Button>
        <Button type="button" variant="secondary" onClick={onCancel} disabled={busy}>
          {t("admin.action.cancel")}
        </Button>
      </div>
    </form>
  );
}

/** A one-sentence preview built from the currently-effective entity terms, so an admin sees the effect of a save
 * immediately in this session, without waiting for the app's own labels fetch (bound to sign-in, not to this card's
 * own saves) to refresh. */
function LabelsPreview() {
  const { t } = useLabels();
  return (
    <p className="text-fg-muted">
      {t("admin.labels.preview.intro")} {t("admin.labels.preview.sentence")}
    </p>
  );
}
