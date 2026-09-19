import { API_ERROR_CODES } from "@qms/api-client";
import { describe, expect, it, vi } from "vitest";
import bn from "./packs/bn.json";
import en from "./packs/en.json";
import {
  createI18n,
  formatTokenNumber,
  loadExtraPacks,
  resolveLanguage,
  SHIPPED_PACKS,
  toWesternDigits,
  type Pack,
} from "./index";

const ENABLED = ["en", "bn"] as const;

describe("resolveLanguage (FR-I18N-003)", () => {
  const base = { system: "en", enabled: ENABLED };

  it("user preference beats device, site and system", () => {
    expect(resolveLanguage({ ...base, user: "bn", device: ["en"], site: "en" })).toBe("bn");
  });
  it("device setting beats site and system", () => {
    expect(resolveLanguage({ ...base, device: ["bn"], site: "en" })).toBe("bn");
  });
  it("site default beats system default", () => {
    expect(resolveLanguage({ ...base, site: "bn" })).toBe("bn");
  });
  it("falls back to system default", () => {
    expect(resolveLanguage(base)).toBe("en");
  });
  it("skips unsupported and blank preferences instead of returning them", () => {
    expect(resolveLanguage({ ...base, user: "fr", device: ["", "de"], site: "bn" })).toBe("bn");
  });
  it("matches region tags to their language and takes the first supported device language", () => {
    expect(resolveLanguage({ ...base, device: ["fr-FR", "bn-BD", "en-US"] })).toBe("bn");
  });
});

describe("shipped packs (FR-I18N-001)", () => {
  it("English and Bangla have exactly the same keys, none blank", () => {
    expect(Object.keys(bn).sort()).toEqual(Object.keys(en).sort());
    for (const [key, value] of [...Object.entries(en), ...Object.entries(bn)]) {
      expect(value.trim(), key).not.toBe("");
    }
  });

  it("has a message for every API error code and the client-side codes", () => {
    for (const code of [...API_ERROR_CODES, "network_error", "unexpected_response"]) {
      expect(en, code).toHaveProperty(`errors.${code}`);
      expect(bn, code).toHaveProperty(`errors.${code}`);
    }
  });

  it("names every staff role (role display names are the only localisable part, FR-CFG-101)", () => {
    for (const role of ["system_admin", "org_admin", "team_admin", "agent", "reception_operator"]) {
      expect(en).toHaveProperty(`roles.${role}`);
      expect(bn).toHaveProperty(`roles.${role}`);
    }
  });
});

describe("translator fallback (FR-I18N-011)", () => {
  // Spreading a Record<string, Pack> drops the shipped keys from the inferred type, so name them explicitly.
  const shippedBn = SHIPPED_PACKS.bn as Pack;
  const shippedEn = SHIPPED_PACKS.en as Pack;
  const packs: Record<string, Pack> & { en: Pack; bn: Pack } = {
    en: shippedEn,
    bn: shippedBn,
    ur: { messages: { "auth.title": "سائن ان" }, meta: { locale: "ur-PK", numerals: "latn" } },
  };

  it("uses the requested language", () => {
    expect(createI18n({ packs, language: "bn", systemDefault: "en" }).t("auth.title")).toBe(bn["auth.title"]);
  });

  it("falls back to the site default, never a raw key or empty string", () => {
    const i18n = createI18n({ packs, language: "ur", siteDefault: "bn", systemDefault: "en" });
    expect(i18n.t("auth.username")).toBe(bn["auth.username"]);
  });

  it("falls back to the system default when the site default lacks the key too", () => {
    const sparse = { ...packs, bn: { ...packs.bn, messages: { "fallback.text": "x" } } };
    const i18n = createI18n({ packs: sparse, language: "ur", siteDefault: "bn", systemDefault: "en" });
    expect(i18n.t("auth.username")).toBe(en["auth.username"]);
  });

  it("treats a blank translation as missing", () => {
    const blank = { ...packs, bn: { ...packs.bn, messages: { ...packs.bn.messages, "auth.title": "  " } } };
    expect(createI18n({ packs: blank, language: "bn", systemDefault: "en" }).t("auth.title")).toBe(en["auth.title"]);
  });

  it("returns the generic fallback text for a key that exists nowhere", () => {
    const text = createI18n({ packs, language: "bn", systemDefault: "en" }).t("no.such.key");
    expect(text).not.toBe("no.such.key");
    expect(text.trim()).not.toBe("");
  });

  it("interpolates named parameters", () => {
    const custom = { en: { ...packs.en, messages: { ...packs.en.messages, "test.hi": "Hello {name}" } } };
    expect(createI18n({ packs: custom, language: "en", systemDefault: "en" }).t("test.hi", { name: "Rahim" })).toBe(
      "Hello Rahim",
    );
  });
});

describe("numerals, dates and times (FR-I18N-020, FR-I18N-021)", () => {
  const BENGALI_DIGIT = /[০-৯]/;
  const at = new Date("2026-09-19T15:30:00+06:00");

  it("token numbers are always Western Arabic digits, in any language", () => {
    expect(formatTokenNumber("S-042")).toBe("S-042");
    expect(formatTokenNumber("S-০৪২")).toBe("S-042");
    expect(createI18n({ packs: SHIPPED_PACKS, language: "bn", systemDefault: "en" }).formatToken("A-১২")).toBe("A-12");
    expect(toWesternDigits("১২৩٤٥٦")).toBe("123456");
  });

  it("numbers render in Bengali digits for the bn pack and Western digits for en", () => {
    const bnI18n = createI18n({ packs: SHIPPED_PACKS, language: "bn", systemDefault: "en" });
    const enI18n = createI18n({ packs: SHIPPED_PACKS, language: "en", systemDefault: "en" });
    expect(bnI18n.formatNumber(1234)).toMatch(BENGALI_DIGIT);
    expect(enI18n.formatNumber(1234)).toBe("1,234");
  });

  it("honours a per-site 12/24-hour preference", () => {
    const twelve = createI18n({ packs: SHIPPED_PACKS, language: "en", systemDefault: "en", clock: "12h" });
    const twentyFour = createI18n({ packs: SHIPPED_PACKS, language: "en", systemDefault: "en", clock: "24h" });
    expect(twelve.formatTime(at, { timeZone: "Asia/Dhaka" })).toMatch(/3:30\s?PM/i);
    expect(twentyFour.formatTime(at, { timeZone: "Asia/Dhaka" })).toBe("15:30");
  });

  it("formats times in Bengali digits for the bn pack", () => {
    const i18n = createI18n({ packs: SHIPPED_PACKS, language: "bn", systemDefault: "en", clock: "24h" });
    expect(i18n.formatTime(at, { timeZone: "Asia/Dhaka" })).toMatch(BENGALI_DIGIT);
  });

  it("formats dates and currency by the rendering locale", () => {
    const i18n = createI18n({ packs: SHIPPED_PACKS, language: "en", systemDefault: "en" });
    expect(i18n.formatDate(at, { timeZone: "Asia/Dhaka" })).toContain("2026");
    expect(i18n.formatCurrency(1500, "BDT")).toMatch(/1,500/);
  });
});

describe("additional packs without a code release (FR-I18N-001)", () => {
  it("loads packs listed in the runtime index", async () => {
    const fetchImpl = vi.fn(async (url: string) => {
      if (url.endsWith("/index.json")) {
        return new Response(JSON.stringify({ languages: [{ code: "ur", locale: "ur-PK", numerals: "latn" }] }));
      }
      if (url.endsWith("/ur.json")) return new Response(JSON.stringify({ "auth.title": "سائن ان" }));
      return new Response("", { status: 404 });
    });

    const extra = await loadExtraPacks(fetchImpl as unknown as typeof fetch);

    expect(extra.ur?.messages["auth.title"]).toBe("سائن ان");
    expect(extra.ur?.meta.locale).toBe("ur-PK");
  });

  it("returns nothing, without throwing, when no index exists", async () => {
    const fetchImpl = vi.fn(async () => new Response("", { status: 404 }));
    await expect(loadExtraPacks(fetchImpl as unknown as typeof fetch)).resolves.toEqual({});
  });
});
