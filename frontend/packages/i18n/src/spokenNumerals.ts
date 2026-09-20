import { toWesternDigits } from "./numerals";

/**
 * Digit words for voice announcements (ticket 29, FR-DSP-030, FR-I18N-020): a token number is always rendered in
 * Western Arabic digits on screen, but spoken digit by digit in the language of the announcement -- Bangla audio
 * speaks the number in Bangla, never in the digits' Western Arabic shape (ADR-0011). English falls back for any
 * language this build has no digit words for.
 */
const DIGIT_WORDS: Record<string, readonly string[]> = {
  en: ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"],
  bn: ["শূন্য", "এক", "দুই", "তিন", "চার", "পাঁচ", "ছয়", "সাত", "আট", "নয়"],
};

/** The digits of `value` (Western, Bengali or Arabic-Indic) as spoken words, oldest digit first. Non-digits are dropped. */
export function spokenDigitWords(value: string, language: string): string[] {
  const words = DIGIT_WORDS[language] ?? DIGIT_WORDS.en!;
  return toWesternDigits(value)
    .split("")
    .filter((ch) => ch >= "0" && ch <= "9")
    .map((ch) => words[Number(ch)]!);
}

/** `value`'s digits spoken as one phrase, e.g. "0" "4" "5" -> "zero four five" (en) / "শূন্য চার পাঁচ" (bn). */
export function spokenNumber(value: string, language: string): string {
  return spokenDigitWords(value, language).join(" ");
}
