"use client";

import { useI18n } from "@qms/i18n/react";
import { useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { useAuth } from "../lib/auth";

/** Sends a signed-out visitor to the login screen. It is a convenience only: the API enforces access (FR-CFG-103). */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  const { status } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (status === "anonymous") router.replace("/login/");
  }, [status, router]);

  if (status === "authenticated") return <>{children}</>;
  return <p className="text-fg-muted">{t("common.loading")}</p>;
}
