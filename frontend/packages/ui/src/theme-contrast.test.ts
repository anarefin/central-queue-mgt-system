import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { contrastRatio } from "./color-contrast";

/**
 * Reads the actual token values out of theme.css (rather than duplicating them here), so a future edit that
 * weakens a colour pair's contrast fails this test instead of silently shipping (SRS §27.5 accessibility baseline).
 */
const themePath = join(dirname(fileURLToPath(import.meta.url)), "theme.css");
const css = readFileSync(themePath, "utf8");

function extractBlock(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = css.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`, "m"));
  if (!match) throw new Error(`Selector not found in theme.css: ${selector}`);
  return match[1] ?? "";
}

function parseDeclarations(block: string): Map<string, string> {
  const map = new Map<string, string>();
  const withoutComments = block.replace(/\/\*[\s\S]*?\*\//g, "");
  for (const statement of withoutComments.split(";")) {
    const trimmed = statement.trim();
    if (!trimmed.startsWith("--")) continue;
    const colon = trimmed.indexOf(":");
    if (colon === -1) continue;
    map.set(trimmed.slice(0, colon).trim(), trimmed.slice(colon + 1).trim());
  }
  return map;
}

function resolve(value: string, scope: Map<string, string>): string {
  let result = value;
  const varPattern = /var\((--[a-zA-Z0-9-]+)\)/;
  let match: RegExpMatchArray | null;
  const seen = new Set<string>();
  while ((match = result.match(varPattern))) {
    const name = match[1] as string;
    if (seen.has(name)) throw new Error(`Circular reference resolving ${name} in theme.css`);
    seen.add(name);
    const resolved = scope.get(name);
    if (resolved === undefined) throw new Error(`Unresolved CSS variable ${name} while parsing theme.css`);
    result = result.replace(match[0], resolved);
  }
  return result.trim();
}

const themeScale = parseDeclarations(extractBlock("@theme"));
const lightRaw = parseDeclarations(extractBlock(":root"));
const darkRaw = parseDeclarations(extractBlock(':root[data-theme="dark"]'));

const lightScope = new Map([...themeScale, ...lightRaw]);
const darkScope = new Map([...themeScale, ...lightRaw, ...darkRaw]);

/** Every text/background token pair the design system actually pairs up (Button, Badge, body text on the page). */
const pairs: [fg: string, bg: string][] = [
  ["--qms-raw-fg", "--qms-raw-surface"],
  ["--qms-raw-fg", "--qms-raw-surface-muted"],
  ["--qms-raw-fg-muted", "--qms-raw-surface"],
  ["--qms-raw-fg-muted", "--qms-raw-surface-muted"],
  ["--qms-raw-primary-fg", "--qms-raw-primary"],
  ["--qms-raw-ok", "--qms-raw-ok-subtle"],
  ["--qms-raw-warn", "--qms-raw-warn-subtle"],
  ["--qms-raw-danger", "--qms-raw-danger-subtle"],
  ["--qms-raw-info", "--qms-raw-info-subtle"],
];

describe.each([
  ["light", lightScope],
  ["dark", darkScope],
] as const)("theme.css %s palette", (themeName, scope) => {
  it.each(pairs)("%s on %s meets WCAG AA (4.5:1 body text)", (fgVar, bgVar) => {
    const fg = resolve(`var(${fgVar})`, scope);
    const bg = resolve(`var(${bgVar})`, scope);
    expect(contrastRatio(fg, bg)).toBeGreaterThanOrEqual(4.5);
  });
});
