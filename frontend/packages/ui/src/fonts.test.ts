import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { describe, expect, it } from "vitest";

const require = createRequire(import.meta.url);
const fontkit = require("fontkit") as { create(buffer: Buffer): any };

function loadBengali(weight: 400 | 600) {
  const file = require.resolve(`@fontsource/noto-sans-bengali/files/noto-sans-bengali-bengali-${weight}-normal.woff2`);
  return fontkit.create(readFileSync(file));
}

/** Assigned Bengali code points the UI needs, as inclusive [from, to] ranges (Unicode block U+0980–U+09FF). */
const CORE: ReadonlyArray<readonly [number, number]> = [
  [0x0981, 0x0983], // candrabindu, anusvara, visarga
  [0x0985, 0x098c], // independent vowels
  [0x098f, 0x0990],
  [0x0993, 0x0994],
  [0x0995, 0x09a8], // consonants
  [0x09aa, 0x09b0],
  [0x09b2, 0x09b2],
  [0x09b6, 0x09b9],
  [0x09bc, 0x09bc], // nukta
  [0x09be, 0x09c4], // vowel signs
  [0x09c7, 0x09c8],
  [0x09cb, 0x09cc],
  [0x09cd, 0x09ce], // hasanta (virama, which forms conjuncts) and khanda ta
  [0x09e6, 0x09ef], // Bengali digits
];

/** FR-I18N-023: bundled fonts cover Bengali script including conjuncts. */
describe.each([400, 600] as const)("bundled Bengali font, weight %i", (weight) => {
  const font = loadBengali(weight);

  it("covers the assigned Bengali letters, signs, digits and the virama", () => {
    const missing: string[] = [];
    for (const [from, to] of CORE) {
      for (let code = from; code <= to; code++) {
        if (!font.hasGlyphForCodePoint(code)) missing.push(`U+${code.toString(16).toUpperCase().padStart(4, "0")}`);
      }
    }
    expect(missing).toEqual([]);
  });

  it("carries the GSUB features that build conjuncts (akhn, half, vatu, cjct)", () => {
    // fontkit's availableFeatures lists GPOS only, so read the GSUB feature list directly.
    const features: string[] = font.GSUB.featureList.map((feature: { tag: string }) => feature.tag);
    expect(features).toEqual(expect.arrayContaining(["akhn", "half", "vatu", "cjct"]));
  });

  it("actually forms conjuncts: ক্ষ becomes one ligature glyph and স্ত a half form", () => {
    // Three code points each (consonant, virama, consonant); shaping must fuse them.
    expect(font.layout("ক্ষ").glyphs).toHaveLength(1);
    expect(font.layout("স্ত").glyphs.length).toBeLessThan(3);
  });
});
