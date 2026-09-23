import { readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const SRC = path.resolve(__dirname, "..");

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

describe("no qms- utility classes remain in apps/console/src (ticket 64)", () => {
  it("every className is a Tailwind utility or a packages/ui component, not a legacy qms- CSS class", () => {
    const offenders: string[] = [];
    for (const file of walk(SRC)) {
      const text = readFileSync(file, "utf8");
      const classNameBlocks = text.match(/className\s*=\s*(\{[^}]*\}|"[^"]*"|`[^`]*`)/g) ?? [];
      for (const block of classNameBlocks) {
        const matches = block.match(/qms-[a-zA-Z-]+/g) ?? [];
        for (const match of matches) offenders.push(`${path.relative(SRC, file)}: ${match}`);
      }
    }
    expect(offenders).toEqual([]);
  });
});
