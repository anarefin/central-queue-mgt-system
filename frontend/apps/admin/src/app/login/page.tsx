"use client";

import { Page } from "@qms/ui";
import { BrandHeader } from "../../components/BrandHeader";
import { HealthPanel } from "../../components/HealthPanel";
import { LoginForm } from "../../components/LoginForm";

export default function LoginPage() {
  return (
    <Page>
      <BrandHeader />
      <LoginForm />
      <HealthPanel />
    </Page>
  );
}
