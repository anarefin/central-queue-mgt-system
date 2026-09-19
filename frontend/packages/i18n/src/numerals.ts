const BENGALI_ZERO = 0x09e6;
const ARABIC_INDIC_ZERO = 0x0660;

/** Converts Bengali and Arabic-Indic digits to Western Arabic digits; leaves everything else alone. */
export function toWesternDigits(value: string): string {
  return value.replace(/[০-৯٠-٩]/g, (digit) => {
    const code = digit.codePointAt(0)!;
    return String(code >= BENGALI_ZERO && code <= BENGALI_ZERO + 9 ? code - BENGALI_ZERO : code - ARABIC_INDIC_ZERO);
  });
}

/** Token numbers render in Western Arabic digits on every surface, whatever the language (FR-I18N-020). */
export function formatTokenNumber(token: string): string {
  return toWesternDigits(token);
}
