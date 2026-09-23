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

describe("no legacy qms- classes or hard-coded colours remain in apps/display/src (ticket 65)", () => {
  it("every className is a Tailwind utility or a packages/ui component, not a legacy qms- CSS class", () => {
    const offenders: string[] = [];
    for (const file of walk(SRC)) {
      const text = readFileSync(file, "utf8");
      const classNameBlocks = text.match(/className\s*=\s*(\{[^}]*\}|"[^"]*"|`[^`]*`)/g) ?? [];
      for (const block of classNameBlocks) {
        const matches = block.match(/qms-[a-zA-Z-]+/g) ?? [];
        offenders.push(...matches.map((match) => `${path.relative(SRC, file)}: ${match}`));
      }
    }
    expect(offenders).toEqual([]);
  });

  it("no hard-coded hex or rgb()/rgba() colour literal appears in a className or style attribute", () => {
    const offenders: string[] = [];
    for (const file of walk(SRC)) {
      const text = readFileSync(file, "utf8");
      const attrBlocks = text.match(/\b(?:className|style)\s*=\s*(\{[\s\S]*?\}|"[^"]*"|`[^`]*`)/g) ?? [];
      for (const block of attrBlocks) {
        if (/#[0-9a-fA-F]{3,8}\b/.test(block) || /\brgba?\(/.test(block)) {
          offenders.push(`${path.relative(SRC, file)}: ${block.slice(0, 120)}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });
});
