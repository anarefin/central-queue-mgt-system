import { describe, expect, it } from "vitest";
import { activeLanguage } from "./languageCycle";

describe("activeLanguage (ticket 30, FR-I18N-005)", () => {
  it("cycles through the languages at the configured interval", () => {
    expect(activeLanguage(["bn", "en"], 10, 0)).toEqual({ language: "bn", sideBySide: false, languages: ["bn", "en"] });
    expect(activeLanguage(["bn", "en"], 10, 9_999)).toEqual({ language: "bn", sideBySide: false, languages: ["bn", "en"] });
    expect(activeLanguage(["bn", "en"], 10, 10_000)).toEqual({ language: "en", sideBySide: false, languages: ["bn", "en"] });
    expect(activeLanguage(["bn", "en"], 10, 20_000)).toEqual({ language: "bn", sideBySide: false, languages: ["bn", "en"] }); // wraps
  });

  it("renders side by side instead of cycling when the interval is 0", () => {
    expect(activeLanguage(["bn", "en"], 0, 50_000)).toEqual({ language: "bn", sideBySide: true, languages: ["bn", "en"] });
  });

  it("a single-language cycle is never side by side, whatever the interval", () => {
    expect(activeLanguage(["en"], 0, 0)).toEqual({ language: "en", sideBySide: false, languages: ["en"] });
  });

  it("falls back to English when the cycle is empty", () => {
    expect(activeLanguage([], 10, 0)).toEqual({ language: "en", sideBySide: false, languages: ["en"] });
  });
});
