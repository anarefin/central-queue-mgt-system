"use client";

import { useEffect, useState } from "react";
import { useApi } from "../lib/runtime";

/**
 * The signed-out screens' own brand mark (ticket 63): the org's logo, or its name where no logo is configured.
 * `GET /branding/theme` is the same public, unauthenticated read `RuntimeProvider` already calls to theme the
 * page's colours (ticket 62) — this just also keeps the name/logo, which `applyBrand` does not expose.
 */
export function BrandHeader() {
  const { client } = useApi();
  const [brand, setBrand] = useState<{ orgName: string; logoUrl: string | null } | null>(null);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client.branding.theme().then(
      (theme) => !cancelled && setBrand({ orgName: theme.org_name, logoUrl: theme.logo_url }),
      () => undefined,
    );
    return () => {
      cancelled = true;
    };
  }, [client]);

  if (!brand) return null;

  return (
    <div className="flex flex-col items-center gap-2 text-center">
      {brand.logoUrl ? (
        <img src={brand.logoUrl} alt={brand.orgName} className="max-h-16 max-w-32" />
      ) : (
        <span className="text-lg font-semibold text-primary">{brand.orgName}</span>
      )}
    </div>
  );
}
