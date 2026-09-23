import { readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const SRC = path.resolve(__dirname, "..");

/**
 * The one intentional exception: `qms-print-slip` is the cross-app print-visibility hook class that
 * `packages/ui`'s `theme.css` `@media print` rule targets by name (shared with the kiosk app's own token slip,
 * ticket 25/27, FR-CFG-032/FR-ISS-016). It is a functional selector contract between apps, not one of the
 * `qms-*` styling classes ticket 63's Tailwind migration replaced, and changing it is out of this ticket's scope.
 */
const ALLOWED = new Set(["qms-print-slip"]);

function* walk(dir: string): Generator<string> {
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    const stat = statSync(full);
    if (stat.isDirectory()) {
      yield* walk(full);
    } else if (/\.tsx?$/.test(entry) && !/\.test\.tsx?$/.test(entry)) {
      yield full;
    }
  }
}

describe("no qms- utility classes remain in apps/admin/src (ticket 63)", () => {
  it("every className is a Tailwind utility or a packages/ui component, not a legacy qms- CSS class", () => {
    const offenders: string[] = [];
    for (const file of walk(SRC)) {
      const text = readFileSync(file, "utf8");
      const classNameBlocks = text.match(/className\s*=\s*(\{[^}]*\}|"[^"]*"|`[^`]*`)/g) ?? [];
      for (const block of classNameBlocks) {
        const matches = block.match(/qms-[a-zA-Z-]+/g) ?? [];
        for (const match of matches) {
          if (!ALLOWED.has(match)) offenders.push(`${path.relative(SRC, file)}: ${match}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });
});
