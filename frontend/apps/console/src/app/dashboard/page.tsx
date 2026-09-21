"use client";

import { Page } from "@qms/ui";
import { Suspense } from "react";
import { LiveDashboard } from "../../components/LiveDashboard";
import { RequireAuth } from "../../components/RequireAuth";

export default function DashboardPage() {
  return (
    <Page>
      <RequireAuth>
        <Suspense>
          <LiveDashboard />
        </Suspense>
      </RequireAuth>
    </Page>
  );
}
