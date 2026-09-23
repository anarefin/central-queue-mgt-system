import { describe, expect, it } from "vitest";
import {
  counterLabel,
  noticeDates,
  noticeSortOrder,
  optionalText,
  parseLanguageList,
  reportRange,
  requiredText,
  siteCode,
  siteDefaultLanguage,
  siteDisplayOrder,
  siteEnabledLanguages,
  siteTimezone,
  zoneChimeVolume,
  zoneMaxAnnounceQueueDepth,
  zoneQuietPeriod,
} from "./validators";

describe("requiredText / optionalText (SiteRules.required/optional)", () => {
  it.each([
    ["", "validation.required"],
    ["   ", "validation.required"],
    ["ok", undefined],
    ["x".repeat(30), undefined],
    ["x".repeat(31), "validation.tooLong"],
  ])("requiredText(%j, 30) -> %s", (value, expected) => {
    expect(requiredText(value, 30)).toBe(expected);
  });

  it.each([
    ["", undefined],
    ["x".repeat(30), undefined],
    ["x".repeat(31), "validation.tooLong"],
  ])("optionalText(%j, 30) -> %s", (value, expected) => {
    expect(optionalText(value, 30)).toBe(expected);
  });
});

describe("siteCode (SiteRules.code)", () => {
  it.each([
    ["", "validation.required"],
    ["has space", "validation.site.code.pattern"],
    ["bad!char", "validation.site.code.pattern"],
    ["MAIN-01_a", undefined],
    ["x".repeat(32), undefined],
    ["x".repeat(33), "validation.tooLong"],
  ])("siteCode(%j) -> %s", (value, expected) => {
    expect(siteCode(value)).toBe(expected);
  });
});

describe("siteTimezone (SiteRules.timezone)", () => {
  it("accepts a real IANA zone", () => {
    expect(siteTimezone("Asia/Dhaka")).toBeUndefined();
    expect(siteTimezone("America/New_York")).toBeUndefined();
  });

  it("is required", () => {
    expect(siteTimezone("")).toBe("validation.required");
  });

  it("rejects a zone that does not exist", () => {
    expect(siteTimezone("Mars/Olympus")).toBe("validation.site.timezone.invalid");
  });
});

describe("parseLanguageList", () => {
  it("trims entries and preserves order", () => {
    expect(parseLanguageList(" bn ,  en ")).toEqual(["bn", "en"]);
  });

  it("drops empty entries from stray separators", () => {
    expect(parseLanguageList("bn,, en,")).toEqual(["bn", "en"]);
  });

  it("returns an empty list for blank input", () => {
    expect(parseLanguageList("   ")).toEqual([]);
  });
});

describe("siteEnabledLanguages (SiteRules.languages)", () => {
  it("rejects an empty list", () => {
    expect(siteEnabledLanguages("")).toBe("validation.site.languages.empty");
    expect(siteEnabledLanguages("   ")).toBe("validation.site.languages.empty");
  });

  it("accepts a unique, ordered list", () => {
    expect(siteEnabledLanguages("bn, en")).toBeUndefined();
  });

  it("flags a duplicate by position, wherever it repeats", () => {
    expect(siteEnabledLanguages("bn, en, bn")).toBe("validation.site.languages.duplicate");
    expect(siteEnabledLanguages("en, en")).toBe("validation.site.languages.duplicate");
  });
});

describe("siteDefaultLanguage (SiteRules.languages)", () => {
  it("accepts a default language that is enabled", () => {
    expect(siteDefaultLanguage("bn", "bn, en")).toBeUndefined();
  });

  it("rejects a default language missing from the enabled list", () => {
    expect(siteDefaultLanguage("bn", "en")).toBe("validation.site.defaultLanguage.notEnabled");
  });

  it("stays silent when the enabled-languages list is itself invalid, so only one error shows", () => {
    expect(siteDefaultLanguage("bn", "")).toBeUndefined();
    expect(siteDefaultLanguage("bn", "en, en")).toBeUndefined();
  });
});

describe("siteDisplayOrder (SiteRules.displayOrder)", () => {
  it.each([
    [0, undefined],
    [100_000, undefined],
    [-1, "validation.site.displayOrder.range"],
    [100_001, "validation.site.displayOrder.range"],
  ])("siteDisplayOrder(%d) -> %s", (value, expected) => {
    expect(siteDisplayOrder(value)).toBe(expected);
  });
});

describe("zoneChimeVolume (SiteRules.chimeVolume)", () => {
  it.each([
    [0, undefined],
    [100, undefined],
    [-1, "validation.zone.chimeVolume.range"],
    [101, "validation.zone.chimeVolume.range"],
  ])("zoneChimeVolume(%d) -> %s", (value, expected) => {
    expect(zoneChimeVolume(value)).toBe(expected);
  });
});

describe("zoneMaxAnnounceQueueDepth (SiteRules.maxAnnounceQueueDepth)", () => {
  it.each([
    [1, undefined],
    [20, undefined],
    [0, "validation.zone.maxAnnounceQueueDepth.range"],
    [21, "validation.zone.maxAnnounceQueueDepth.range"],
  ])("zoneMaxAnnounceQueueDepth(%d) -> %s", (value, expected) => {
    expect(zoneMaxAnnounceQueueDepth(value)).toBe(expected);
  });
});

describe("zoneQuietPeriod (SiteRules.quietPeriodComplete)", () => {
  it("accepts both ends set", () => {
    expect(zoneQuietPeriod("22:00", "06:00")).toBeUndefined();
  });

  it("accepts neither end set", () => {
    expect(zoneQuietPeriod("", "")).toBeUndefined();
  });

  it("rejects one end without the other", () => {
    expect(zoneQuietPeriod("22:00", "")).toBe("validation.zone.quietPeriod.incomplete");
    expect(zoneQuietPeriod("", "06:00")).toBe("validation.zone.quietPeriod.incomplete");
  });
});

describe("counterLabel", () => {
  it.each([
    ["", "validation.required"],
    ["x".repeat(30), undefined],
    ["x".repeat(31), "validation.tooLong"],
  ])("counterLabel(%j) -> %s", (value, expected) => {
    expect(counterLabel(value)).toBe(expected);
  });
});

describe("noticeDates (NoticeRules.dates)", () => {
  it("rejects an end equal to the start", () => {
    expect(noticeDates("2026-06-01T09:00", "2026-06-01T09:00")).toBe("validation.notice.endsAt.notAfterStarts");
  });

  it("rejects an end before the start", () => {
    expect(noticeDates("2026-06-02T09:00", "2026-06-01T09:00")).toBe("validation.notice.endsAt.notAfterStarts");
  });

  it("accepts an end strictly after the start", () => {
    expect(noticeDates("2026-06-01T09:00", "2026-06-01T09:01")).toBeUndefined();
  });

  it("stays silent while either end is still unset", () => {
    expect(noticeDates("", "")).toBeUndefined();
    expect(noticeDates("2026-06-01T09:00", "")).toBeUndefined();
    expect(noticeDates("", "2026-06-01T09:00")).toBeUndefined();
  });
});

describe("noticeSortOrder (NoticeRules.sortOrder)", () => {
  it.each([
    [0, undefined],
    [1000, undefined],
    [-1, "validation.notice.sortOrder.range"],
    [1001, "validation.notice.sortOrder.range"],
  ])("noticeSortOrder(%d) -> %s", (value, expected) => {
    expect(noticeSortOrder(value)).toBe(expected);
  });
});

describe("reportRange (SRS §16 report cards)", () => {
  it("accepts from equal to to", () => {
    expect(reportRange("2026-09-01", "2026-09-01")).toBeUndefined();
  });

  it("accepts from before to", () => {
    expect(reportRange("2026-09-01", "2026-09-30")).toBeUndefined();
  });

  it("rejects from after to", () => {
    expect(reportRange("2026-09-30", "2026-09-01")).toBe("validation.reports.range.invalid");
  });

  it("stays silent while either end is unset", () => {
    expect(reportRange("", "")).toBeUndefined();
    expect(reportRange("2026-09-01", "")).toBeUndefined();
    expect(reportRange("", "2026-09-01")).toBeUndefined();
  });
});
