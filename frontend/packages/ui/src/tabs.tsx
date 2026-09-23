"use client";

import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { cn } from "./cn";

export interface TabItem {
  value: string;
  label: ReactNode;
  content: ReactNode;
}

/** Roving tabindex (WAI-ARIA APG "Tabs"): only the active tab is in the Tab order; arrow keys move focus and selection together. */
export function Tabs({ items, defaultValue, label }: { items: TabItem[]; defaultValue?: string; label: string }) {
  const [active, setActive] = useState(defaultValue ?? items[0]?.value);
  const tabRefs = useRef<Record<string, HTMLButtonElement | null>>({});
  const baseId = useId();

  function focusTab(index: number) {
    const item = items[index];
    if (!item) return;
    setActive(item.value);
    tabRefs.current[item.value]?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const currentIndex = items.findIndex((item) => item.value === active);
    if (event.key === "ArrowRight" || event.key === "ArrowDown") {
      event.preventDefault();
      focusTab((currentIndex + 1) % items.length);
    } else if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
      event.preventDefault();
      focusTab((currentIndex - 1 + items.length) % items.length);
    } else if (event.key === "Home") {
      event.preventDefault();
      focusTab(0);
    } else if (event.key === "End") {
      event.preventDefault();
      focusTab(items.length - 1);
    }
  }

  return (
    <div>
      <div role="tablist" aria-label={label} onKeyDown={onKeyDown} className="flex gap-1 border-b border-border">
        {items.map((item) => {
          const selected = item.value === active;
          return (
            <button
              key={item.value}
              ref={(el) => {
                tabRefs.current[item.value] = el;
              }}
              type="button"
              role="tab"
              id={`${baseId}-tab-${item.value}`}
              aria-selected={selected}
              aria-controls={`${baseId}-panel-${item.value}`}
              tabIndex={selected ? 0 : -1}
              onClick={() => setActive(item.value)}
              className={cn(
                "px-3 py-2 text-sm font-medium motion-safe:transition-colors",
                "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary",
                selected ? "border-b-2 border-primary text-primary" : "text-fg-muted hover:text-fg",
              )}
            >
              {item.label}
            </button>
          );
        })}
      </div>
      {items.map((item) => (
        <div key={item.value} role="tabpanel" id={`${baseId}-panel-${item.value}`} aria-labelledby={`${baseId}-tab-${item.value}`} hidden={item.value !== active} className="pt-4">
          {item.content}
        </div>
      ))}
    </div>
  );
}
