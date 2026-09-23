"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { createI18n, type I18n } from "./i18n";
import { loadExtraPacks } from "./extra";
import { SHIPPED_PACKS, type Pack } from "./packs";
import { resolveLanguage } from "./resolve";

const I18nContext = createContext<I18n | null>(null);

export interface I18nProviderProps {
  children: ReactNode;
  /** The signed-in user's stored preference, when known. */
  userLanguage?: string | null;
  siteDefault?: string;
  systemDefault?: string;
  clock?: "12h" | "24h";
  /** Fetches packs an installation added at runtime. Pass `false` to skip (tests). */
  loadExtra?: boolean;
}

export function I18nProvider({
  children,
  userLanguage,
  siteDefault,
  systemDefault = "en",
  clock,
  loadExtra = true,
}: I18nProviderProps) {
  const [extra, setExtra] = useState<Record<string, Pack>>({});
  useEffect(() => {
    if (!loadExtra) return;
    let cancelled = false;
    void loadExtraPacks().then((packs) => {
      if (!cancelled) setExtra(packs);
    });
    return () => {
      cancelled = true;
    };
  }, [loadExtra]);

  const i18n = useMemo(() => {
    const packs = { ...SHIPPED_PACKS, ...extra };
    const device = typeof navigator === "undefined" ? [] : navigator.languages;
    const language = resolveLanguage({
      user: userLanguage,
      device,
      site: siteDefault,
      system: systemDefault,
      enabled: Object.keys(packs),
    });
    return createI18n({ packs, language, siteDefault, systemDefault, clock });
  }, [extra, userLanguage, siteDefault, systemDefault, clock]);

  useEffect(() => {
    document.documentElement.lang = i18n.language;
  }, [i18n.language]);

  return <I18nContext.Provider value={i18n}>{children}</I18nContext.Provider>;
}

/** The nearest {@link I18n}: if a {@link LabelsProvider} sits between here and the {@link I18nProvider}, `t()`
 * already carries the seven entity placeholders (`{visitor}`, `{Visitor}`, `{visitors}`, ...) injected — every
 * existing call site keeps working unchanged once a `LabelsProvider` is added near the app's root (ticket 69). */
export function useI18n(): I18n {
  const value = useContext(I18nContext);
  if (!value) throw new Error("useI18n must be used inside <I18nProvider>");
  return value;
}

// ---- terminology remapping (SRS §3.2, ticket 69) ----------------------------------------------------------------

/** The seven visitor-facing nouns a vertical profile may rename, matching the `entity.*` label keys and the pack's
 * own default-noun keys of the same name (SRS §3.2's table). */
export const ENTITY_KEYS = ["visitor", "visitor_id", "service_group", "counter", "agent", "category", "ticket"] as const;
export type EntityKey = (typeof ENTITY_KEYS)[number];

export interface LabelsContextValue {
  /** Every entity key's currently-effective value for the active language: an override if one exists, else the
   * pack's own default noun (`entity.<key>` in `en.json`/`bn.json`) — never a raw key or blank string. */
  entity: Record<EntityKey, string>;
  /** Same function {@link useI18n}'s `t` now is, exposed here too for a component that only imports `useLabels`. */
  t: (key: string, params?: Record<string, string | number>) => string;
}

const LabelsContext = createContext<LabelsContextValue | null>(null);

export interface LabelsProviderProps {
  children: ReactNode;
  /** A precomputed override map for the active language (key to value, e.g. a kiosk/display's own `bootstrap.labels`
   * or a staff app's `client.labels.get(lang)` result already resolved outside this component). Takes priority over
   * `fetchLabels` when both are given; pass `null`/`undefined` while still loading, which resolves every key to the
   * pack's own default noun in the meantime rather than blocking render. */
  labels?: Record<string, string> | null;
  /** Fetches the override map for one language (e.g. `client.labels.get` for a signed-in app, `client.labels.public`
   * for the anonymous visitor pages). Re-run whenever the active language changes; not used when `labels` is given
   * directly. Its failure just leaves every entity key at the pack's own default noun. */
  fetchLabels?: (lang: string) => Promise<Record<string, string>>;
}

/**
 * Capitalises (or, for {@link lowerFirst}, un-capitalises) only the first code point; a no-op on a script with no
 * letter case (Bangla), so `{Visitor}` and `{visitor}` render identically there — exactly what "Bangla has no case"
 * requires without a per-language branch. Every entity value is stored title-case (it also stands alone, e.g. as a
 * nav label), so the lowercase `{visitor}` placeholder needs its own transform rather than just using the stored
 * value as-is — reusing it verbatim rendered "no {counters} yet" as "no Counters yet" until this was added.
 */
function capitalise(value: string): string {
  if (value.length === 0) return value;
  return value[0]!.toUpperCase() + value.slice(1);
}

function lowerFirst(value: string): string {
  if (value.length === 0) return value;
  return value[0]!.toLowerCase() + value.slice(1);
}

/**
 * A plural form is only meaningfully distinct from the singular in English UI copy; every other shipped pack (today
 * just Bangla) marks plurality with a surrounding word or context rather than a noun suffix, so the plural
 * placeholder is documented here as "same as singular" for any language other than `en` rather than guessing at a
 * suffix rule that would be wrong for most scripts. Applied to the value's own last word, so a multi-word label like
 * "Service group" pluralises to "Service groups" rather than duplicating the whole phrase.
 */
function pluralise(value: string, lang: string): string {
  if (lang !== "en") return value;
  const match = /^(.*?)(\S+)$/.exec(value);
  if (!match) return value;
  const [, head, lastWord] = match;
  let suffix: string;
  if (/[sxz]$/i.test(lastWord!) || /(ch|sh)$/i.test(lastWord!)) suffix = `${lastWord}es`;
  else if (/[^aeiou]y$/i.test(lastWord!)) suffix = `${lastWord!.slice(0, -1)}ies`;
  else suffix = `${lastWord}s`;
  return `${head}${suffix}`;
}

/**
 * Wraps the nearest {@link I18nProvider} to inject the seven entity placeholders into every `t()` call below it —
 * both {@link useLabels}'s own `t` and, transparently, every existing {@link useI18n}`().t()` call site in the tree,
 * so no screen anywhere needs to change how it calls `t()` to pick up a renamed term (ticket 69). Nest it directly
 * under `<I18nProvider>` near an app's root.
 */
export function LabelsProvider({ children, labels, fetchLabels }: LabelsProviderProps) {
  const base = useI18n();
  const [fetched, setFetched] = useState<Record<string, string> | null>(null);

  useEffect(() => {
    if (labels !== undefined || !fetchLabels) return;
    let cancelled = false;
    fetchLabels(base.language).then(
      (result) => {
        if (!cancelled) setFetched(result);
      },
      () => {
        if (!cancelled) setFetched(null);
      },
    );
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetchLabels, base.language, labels]);

  const overrides = labels !== undefined ? labels : fetched;

  const entity = useMemo(() => {
    const map = {} as Record<EntityKey, string>;
    for (const key of ENTITY_KEYS) {
      const packDefault = base.t(`entity.${key}`);
      const override = overrides?.[`entity.${key}`];
      map[key] = override && override.trim() !== "" ? override : packDefault;
    }
    return map;
  }, [base, overrides]);

  // `t` itself never changes identity (empty dep array): it reads the live entity map and base `t` through a ref
  // instead of closing over them, so a `useCallback`/`useEffect` elsewhere that depends on `t` (e.g. a session
  // restore keyed on it) never re-fires just because a label finished loading — that cascaded into one duplicate
  // API call per screen until this was refs-based, caught by CounterConsole's own exact-call-count tests.
  const liveRef = useRef({ base, entity });
  liveRef.current = { base, entity };
  const t = useCallback((key: string, params?: Record<string, string | number>): string => {
    const { base: currentBase, entity: currentEntity } = liveRef.current;
    const injected: Record<string, string> = {};
    for (const entityKey of ENTITY_KEYS) {
      const singular = currentEntity[entityKey];
      const pluralParamName = pluralise(entityKey, "en");
      const pluralValue = pluralise(singular, currentBase.language);
      // {visitor}/{ticket}/... (mid-sentence) and {Visitor}/{Ticket}/... (sentence-initial or standalone) plus
      // both plural forms: four forms per entity key, both derived from the one stored value regardless of its
      // own casing.
      injected[entityKey] = lowerFirst(singular);
      injected[capitalise(entityKey)] = capitalise(singular);
      injected[pluralParamName] = lowerFirst(pluralValue);
      injected[capitalise(pluralParamName)] = capitalise(pluralValue);
    }
    return currentBase.t(key, { ...injected, ...(params ?? {}) });
  }, []);

  // Unlike `t` itself, this object's IDENTITY must change whenever `entity` does: React only re-renders a
  // `useContext(I18nContext)` consumer that never re-renders for its own reasons (a static heading, say) when the
  // provided value's reference changes. Keeping `t` itself stable (above) still avoids the dependent-effect cascade
  // that motivated refs in the first place; this is the other half — a real DOM update once labels finish loading,
  // caught by CounterConsole's own "renders a banking profile's overridden entity terms" test.
  const i18n = useMemo(() => ({ ...base, t }), [base, t, entity]);
  const labelsValue = useMemo(() => ({ entity, t }), [entity, t]);

  return (
    <I18nContext.Provider value={i18n}>
      <LabelsContext.Provider value={labelsValue}>{children}</LabelsContext.Provider>
    </I18nContext.Provider>
  );
}

export function useLabels(): LabelsContextValue {
  const value = useContext(LabelsContext);
  if (!value) throw new Error("useLabels must be used inside <LabelsProvider>");
  return value;
}

/** The subset of a staff app's generated API client this needs: just enough to build `fetchLabels`. */
export interface LabelsClient {
  labels: { get(lang: string): Promise<Record<string, string>> };
}

/**
 * Shared plumbing behind a signed-in app's own `AppLabelsProvider` (ticket 69, SRS §3.2): `GET /labels` needs any
 * authenticated principal, so this waits for sign-in before fetching — before that, every screen just shows the
 * pack's own default noun, which is also what a failed fetch falls back to. Each app still owns its own
 * `AppLabelsProvider` wrapper (one line, passing its own `useApi()`/`useAuth()` through) since those hooks are each
 * app's own context implementation, not something to share; this factors out only the `fetchLabels` construction
 * that was previously duplicated byte-for-byte between the admin and console apps.
 */
export function AuthenticatedLabelsProvider({
  children,
  client,
  authenticated,
}: {
  children: ReactNode;
  client: LabelsClient | null | undefined;
  authenticated: boolean;
}) {
  const fetchLabels = useMemo(() => {
    if (!client || !authenticated) return undefined;
    return (lang: string) => client.labels.get(lang);
  }, [client, authenticated]);

  return <LabelsProvider fetchLabels={fetchLabels}>{children}</LabelsProvider>;
}
