"use client";

import { Fragment, useEffect, useId, useRef, useState, type ComponentType, type ReactNode } from "react";
import { cn } from "./cn";

/** Routes that stay a full-page, centred card outside any `AppShell`-based chrome (ticket 63, matched by ticket
 * 64's console chrome): signed-out visitors have no sidebar, site switcher, user menu or theme toggle to show yet.
 * Shared here so every `AppShell`-based app's own chrome checks the same list instead of redeclaring it. */
export const APP_SHELL_BARE_ROUTES = ["/login/", "/signed-out/"];

export interface AppShellNavItem {
  href: string;
  label: ReactNode;
  active?: boolean;
  /** A heading rendered above this item, shown once whenever it differs from the previous item's own group
   *  (ticket 63's grouped admin sidebar: Setup, Organisation, Queue & services, ...). Omit for an ungrouped list. */
  group?: string;
}

export interface AppShellProps {
  /** The "Skip to content" link's own text: the first focusable element on the page. */
  skipToContentLabel: string;
  menuButtonLabel: string;
  /** Route links, grouped and rendered as the sidebar list (admin's own shape, ticket 63). Omit or leave empty for
   *  a screen with nothing to navigate to, such as the console's serving desk (ticket 64). */
  nav?: AppShellNavItem[];
  /** Arbitrary sidebar content in place of `nav` — the console's live-dashboard filters (ticket 64) are pickers,
   *  not route links, so they do not fit `AppShellNavItem`. Takes over the sidebar slot when given; `nav` is
   *  ignored. */
  sidebar?: ReactNode;
  title?: ReactNode;
  siteSwitcher?: ReactNode;
  userMenu?: ReactNode;
  themeToggle?: ReactNode;
  /** Link element for nav items (e.g. `next/link` for client-side routing); a plain `<a>` when omitted. */
  linkComponent?: "a" | ComponentType<{ href: string; className?: string; children?: ReactNode; onClick?: () => void; "aria-current"?: "page" }>;
  children: ReactNode;
}

/**
 * Sidebar (nav links, or arbitrary `sidebar` content) plus a top bar (title, site switcher, user menu, theme toggle
 * slots), collapsing the sidebar into a menu button at narrow widths. With neither `nav` nor `sidebar` there is
 * nothing to show or toggle, so the sidebar and its menu button are left out entirely (ticket 64's serving desk:
 * "no sidebar on the serving desk"). `<main id="main">` is the skip link's target and the sole landmark for page
 * content (ticket 62).
 */
export function AppShell({ skipToContentLabel, menuButtonLabel, nav = [], sidebar, title, siteSwitcher, userMenu, themeToggle, linkComponent: NavLink = "a", children }: AppShellProps) {
  const [navOpen, setNavOpen] = useState(false);
  const navId = useId();
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  const hasSidebar = sidebar !== undefined || nav.length > 0;

  // Esc closes the mobile drawer and hands focus back to the menu button.
  useEffect(() => {
    if (!navOpen) return;
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setNavOpen(false);
        menuButtonRef.current?.focus();
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [navOpen]);

  return (
    <div className="flex min-h-dvh flex-col bg-surface-muted text-fg md:flex-row">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:start-2 focus:top-2 focus:z-50 focus:rounded-md focus:bg-primary focus:px-4 focus:py-2 focus:text-primary-fg"
      >
        {skipToContentLabel}
      </a>
      {hasSidebar && (
        <>
        {navOpen && <div aria-hidden="true" onClick={() => setNavOpen(false)} className="fixed inset-0 z-30 bg-black/50 md:hidden" />}
        <nav
          id={navId}
          aria-label={menuButtonLabel}
          className={cn(
            "z-40 w-64 max-w-[85vw] shrink-0 overflow-y-auto border-e border-border bg-surface",
            "max-md:fixed max-md:inset-y-0 max-md:start-0 max-md:shadow-lg",
            "md:sticky md:top-0 md:block md:h-dvh md:max-w-none",
            navOpen ? "block" : "hidden",
          )}
        >
          {sidebar ?? (
            <ul className="flex flex-col gap-1 p-3">
              {nav.map((item, index) => (
                <Fragment key={item.href}>
                  {item.group && item.group !== nav[index - 1]?.group && (
                    <li className="px-3 pb-1 pt-4 text-xs font-semibold uppercase tracking-wide text-fg-muted first:pt-0">{item.group}</li>
                  )}
                  <li>
                    <NavLink
                      href={item.href}
                      onClick={() => setNavOpen(false)}
                      aria-current={item.active ? "page" : undefined}
                      className={cn(
                        "block rounded-md px-3 py-2 text-sm font-medium motion-safe:transition-colors",
                        item.active ? "bg-primary text-primary-fg" : "text-fg hover:bg-surface-muted",
                      )}
                    >
                      {item.label}
                    </NavLink>
                  </li>
                </Fragment>
              ))}
            </ul>
          )}
        </nav>
        </>
      )}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-border bg-surface px-4 py-3">
          {hasSidebar && (
            <button
              ref={menuButtonRef}
              type="button"
              onClick={() => setNavOpen((value) => !value)}
              aria-expanded={navOpen}
              aria-controls={navId}
              className="inline-flex size-11 items-center justify-center rounded-md text-fg hover:bg-surface-muted md:hidden"
            >
              <span aria-hidden="true">☰</span>
              <span className="sr-only">{menuButtonLabel}</span>
            </button>
          )}
          {title && <div className="min-w-0 font-semibold">{title}</div>}
          {siteSwitcher}
          <div className="ms-auto flex flex-wrap items-center gap-3">
            {themeToggle}
            {userMenu}
          </div>
        </header>
        <main id="main" className="flex-1 p-4 md:p-6">
          {children}
        </main>
      </div>
    </div>
  );
}
