"use client";

import { Suspense } from "react";
import { LiveDashboard } from "../../components/LiveDashboard";
import { RequireAuth } from "../../components/RequireAuth";

export default function DashboardPage() {
  return (
    <RequireAuth>
      <Suspense>
        <LiveDashboard />
      </Suspense>
    </RequireAuth>
  );
}
