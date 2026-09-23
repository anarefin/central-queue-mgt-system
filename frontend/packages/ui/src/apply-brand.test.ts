import { describe, expect, it } from "vitest";
import { applyBrand, DEFAULT_ACCENT, deriveBrandColors } from "./apply-brand";
import { contrastRatio, relativeLuminance } from "./color-contrast";

describe("deriveBrandColors", () => {
  it("keeps a valid hex accent and derives a readable foreground", () => {
    const { primary, primaryFg } = deriveBrandColors("#0b5fff");
    expect(primary).toBe("#0b5fff");
    expect(contrastRatio(primaryFg, primary)).toBeGreaterThanOrEqual(4.5);
  });

  it("picks near-black text on a light accent, still meeting AA", () => {
    const { primary, primaryFg } = deriveBrandColors("#fef08a");
    expect(primary).toBe("#fef08a");
    expect(primaryFg).toBe("#111827");
    expect(contrastRatio(primaryFg, primary)).toBeGreaterThanOrEqual(4.5);
  });

  it("picks white text on a dark accent, still meeting AA", () => {
    const { primary, primaryFg } = deriveBrandColors("#111111");
    expect(primary).toBe("#111111");
    expect(primaryFg).toBe("#ffffff");
    expect(contrastRatio(primaryFg, primary)).toBeGreaterThanOrEqual(4.5);
  });

  it("derives hover and active as progressively darker shades of the accent", () => {
    const { primary, primaryHover, primaryActive } = deriveBrandColors("#0b5fff");
    const primaryL = relativeLuminance(primary);
    const hoverL = relativeLuminance(primaryHover);
    const activeL = relativeLuminance(primaryActive);
    expect(hoverL).toBeLessThan(primaryL);
    expect(activeL).toBeLessThan(hoverL);
  });

  it.each([
    ["not a colour at all", "cornflowerblue"],
    ["a 3-digit shorthand", "#fff"],
    ["missing the hash", "0b5fff"],
    ["out-of-range hex digits", "#gggggg"],
    ["an empty string", ""],
  ])("falls back to the default accent for %s", (_label, invalidInput) => {
    const { primary } = deriveBrandColors(invalidInput);
    expect(primary).toBe(DEFAULT_ACCENT);
  });

  it.each([
    ["undefined", undefined],
    ["null", null],
  ])("falls back to the default accent when the colour is %s", (_label, missingInput) => {
    const { primary } = deriveBrandColors(missingInput);
    expect(primary).toBe(DEFAULT_ACCENT);
  });
});

describe("applyBrand", () => {
  it("returns the derived colours alongside the org name and logo", () => {
    const result = applyBrand({ primary_color: "#0b5fff", org_name: "Acme QMS", logo_url: "https://example.test/logo.png" });
    expect(result.primaryColor).toBe("#0b5fff");
    expect(result.orgName).toBe("Acme QMS");
    expect(result.logoUrl).toBe("https://example.test/logo.png");
  });

  it("falls back to the default accent when the colour is invalid", () => {
    const result = applyBrand({ primary_color: "not-a-colour" });
    expect(result.primaryColor).toBe(DEFAULT_ACCENT);
  });

  it("falls back to the default accent when branding is entirely absent", () => {
    const result = applyBrand({});
    expect(result.primaryColor).toBe(DEFAULT_ACCENT);
    expect(result.orgName).toBeNull();
    expect(result.logoUrl).toBeNull();
  });

  it("treats a blank org name or logo URL as absent", () => {
    const result = applyBrand({ org_name: "   ", logo_url: "  " });
    expect(result.orgName).toBeNull();
    expect(result.logoUrl).toBeNull();
  });
});
