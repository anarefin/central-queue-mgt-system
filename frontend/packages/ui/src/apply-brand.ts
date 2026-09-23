import { contrastRatio, hexToRgb, isValidHexColor } from "./color-contrast";

/** The org branding fields every app reads, from either `GET /branding/theme` or a device's `/config/bootstrap`. */
export interface BrandConfig {
  primary_color?: string | null;
  logo_url?: string | null;
  org_name?: string | null;
}

export interface AppliedBrand {
  orgName: string | null;
  logoUrl: string | null;
  primaryColor: string;
  primaryHover: string;
  primaryActive: string;
  primaryFg: string;
}

/** theme.css's own default accent (and OrgBranding.DEFAULT's primary_color): what an invalid or missing colour falls back to. */
export const DEFAULT_ACCENT = "#0b5fff";

const WHITE = "#ffffff";
const NEAR_BLACK = "#111827";

function mixTowardBlack(hex: string, amount: number): string {
  const { r, g, b } = hexToRgb(hex);
  const factor = 1 - amount;
  const toHex = (c: number) =>
    Math.max(0, Math.min(255, Math.round(c * factor)))
      .toString(16)
      .padStart(2, "0");
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
}

/**
 * Validates the org's configured accent, falling back to the design system's own default when it is missing or not
 * a 6-digit hex colour, then derives a readable foreground (white or near-black, whichever clears 4.5:1) plus hover
 * and active shades a shade darker each.
 */
export function deriveBrandColors(primaryColor: string | null | undefined): {
  primary: string;
  primaryHover: string;
  primaryActive: string;
  primaryFg: string;
} {
  const primary = isValidHexColor(primaryColor) ? primaryColor.trim() : DEFAULT_ACCENT;
  const primaryFg = contrastRatio(WHITE, primary) >= contrastRatio(NEAR_BLACK, primary) ? WHITE : NEAR_BLACK;
  return {
    primary,
    primaryHover: mixTowardBlack(primary, 0.15),
    primaryActive: mixTowardBlack(primary, 0.3),
    primaryFg,
  };
}

/**
 * Applies the org's brand at runtime: sets `--color-primary` (and its hover/active/fg companions) as an inline style
 * on `<html>`, which every `bg-primary`/`text-primary` utility and `.qms-color-primary` alias already reads, so it
 * overrides theme.css's default regardless of light/dark mode (ticket 62). Every app calls this on start.
 */
export function applyBrand(brand: BrandConfig): AppliedBrand {
  const { primary, primaryHover, primaryActive, primaryFg } = deriveBrandColors(brand.primary_color);

  if (typeof document !== "undefined") {
    const root = document.documentElement.style;
    root.setProperty("--color-primary", primary);
    root.setProperty("--color-primary-hover", primaryHover);
    root.setProperty("--color-primary-active", primaryActive);
    root.setProperty("--color-primary-fg", primaryFg);
  }

  return {
    orgName: brand.org_name?.trim() || null,
    logoUrl: brand.logo_url?.trim() || null,
    primaryColor: primary,
    primaryHover,
    primaryActive,
    primaryFg,
  };
}
