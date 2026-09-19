"use client";

import { Page } from "@qms/ui";
import { HealthPanel } from "../../components/HealthPanel";
import { LoginForm } from "../../components/LoginForm";

export default function LoginPage() {
  return (
    <Page>
      <LoginForm />
      <HealthPanel />
    </Page>
  );
}
