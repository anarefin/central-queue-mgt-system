import { describe, expect, it } from "vitest";
import { spokenDigitWords, spokenNumber } from "./spokenNumerals";

describe("spoken numerals (ticket 29, FR-DSP-030, FR-I18N-020)", () => {
  it("speaks Western Arabic digits as English words", () => {
    expect(spokenNumber("045", "en")).toBe("zero four five");
  });

  it("speaks the same digits in Bangla, correctly pronounced rather than transliterated (Bangla audio speaks the number in Bangla, ADR-0011)", () => {
    expect(spokenNumber("045", "bn")).toBe("শূন্য চার পাঁচ");
  });

  it("converts Bengali digits to their spoken Bangla words too, since a token number may already carry them", () => {
    expect(spokenDigitWords("০৪৫", "bn")).toEqual(["শূন্য", "চার", "পাঁচ"]);
  });

  it("drops any non-digit character, such as a numbering separator", () => {
    expect(spokenNumber("QC-045", "en")).toBe("zero four five");
  });

  it("falls back to English digit words for a language with none configured", () => {
    expect(spokenNumber("7", "fr")).toBe("seven");
  });
});
