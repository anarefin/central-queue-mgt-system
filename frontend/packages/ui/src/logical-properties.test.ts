import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const here = dirname(fileURLToPath(import.meta.url));
const roots = [
  { label: "packages/ui/src", dir: here },
  { label: "apps/*/src", dir: join(here, "../../../apps") },
];

/**
 * Justified exceptions only (ticket 62's "short allow-list for justified cases"): an entry is a substring that, if
 * present on an offending line, excuses it. Add one here with a comment explaining why, rather than loosening the
 * forbidden list below.
 */
const ALLOWLIST: string[] = [];

const EXACT_FORBIDDEN = new Set(["text-left", "text-right", "truncate", "border-l", "border-r", "rounded-l", "rounded-r"]);
const PREFIX_FORBIDDEN = ["ml-", "mr-", "pl-", "pr-", "left-", "right-", "border-l-", "border-r-", "rounded-l-", "rounded-r-", "line-clamp-"];
const PIXEL_SIZE_RE = /^(w|h|min-w|max-w|min-h|max-h)-\[[^\]]*px[^\]]*]$/;

function isForbiddenToken(token: string): boolean {
  if (EXACT_FORBIDDEN.has(token)) return true;
  if (PREFIX_FORBIDDEN.some((prefix) => token.startsWith(prefix))) return true;
  return PIXEL_SIZE_RE.test(token);
}

/** Every string a class-name-shaped Tailwind utility could actually appear in: a literal `className`, or a quoted
    argument passed to this package's `cn()` helper. Deliberately narrow, so English prose elsewhere in a .tsx file
    (comments, translated fallback text) is never mistaken for a utility class. */
function extractClassCandidates(source: string): string[] {
  const candidates: string[] = [];
  const classNameRe = /className\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
  for (const match of source.matchAll(classNameRe)) {
    candidates.push(match[1] ?? match[2] ?? "");
  }
  const cnCallRe = /\b(?:cn|clsx)\(([^)]*)\)/g;
  for (const call of source.matchAll(cnCallRe)) {
    const args = call[1] ?? "";
    const stringLiteralRe = /"([^"]*)"|'([^']*)'/g;
    for (const literal of args.matchAll(stringLiteralRe)) {
      candidates.push(literal[1] ?? literal[2] ?? "");
    }
  }
  return candidates;
}

function listTsxFiles(dir: string): string[] {
  let entries: string[];
  try {
    entries = readdirSync(dir, { recursive: true }) as string[];
  } catch {
    return [];
  }
  return entries.filter((entry) => entry.endsWith(".tsx")).map((entry) => join(dir, entry));
}

describe("logical-property guard (FR-I18N-030, FR-I18N-031)", () => {
  for (const { label, dir } of roots) {
    const files = listTsxFiles(dir);

    it(`${label} has .tsx files to scan`, () => {
      expect(files.length).toBeGreaterThan(0);
    });

    it(`${label} uses only logical-property Tailwind utilities`, () => {
      const violations: string[] = [];
      for (const file of files) {
        const source = readFileSync(file, "utf8");
        if (ALLOWLIST.some((allowed) => source.includes(allowed))) continue;
        for (const candidate of extractClassCandidates(source)) {
          for (const token of candidate.split(/\s+/).filter(Boolean)) {
            if (isForbiddenToken(token)) {
              violations.push(`${file}: forbidden physical-property utility "${token}"`);
            }
          }
        }
      }
      expect(violations).toEqual([]);
    });
  }
});
