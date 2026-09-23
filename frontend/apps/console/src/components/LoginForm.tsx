"use client";

import { ApiRequestError } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, TextField } from "@qms/ui";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useAuth } from "../lib/auth";

export function LoginForm() {
  const { t } = useI18n();
  const { login } = useAuth();
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      await login(username.trim(), password);
      setPassword("");
      router.replace("/");
    } catch (cause) {
      setError(describe(cause, t));
    } finally {
      setPending(false);
    }
  }

  return (
    <Card>
      <h1 className="text-lg font-semibold text-fg">{t("auth.title")}</h1>
      <form className="flex flex-col gap-4" onSubmit={submit}>
        <TextField
          id="username"
          label={t("auth.username")}
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          required
        />
        <TextField
          id="password"
          label={t("auth.password")}
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          required
        />
        {error && <ErrorAlert>{error}</ErrorAlert>}
        <Button type="submit" disabled={pending}>
          {pending ? t("auth.signingIn") : t("auth.submit")}
        </Button>
      </form>
    </Card>
  );
}

/** A localised message for a failed sign-in; the code, not the server's text, chooses it. */
function describe(cause: unknown, t: (key: string, params?: Record<string, string | number>) => string): string {
  if (!(cause instanceof ApiRequestError)) return t("errors.network_error");
  const message = t(`errors.${cause.code}`);
  const seconds = cause.body?.details?.retry_after_seconds;
  if (cause.code === "account_locked" && typeof seconds === "number") {
    return `${message} ${t("auth.retryIn", { minutes: Math.max(1, Math.ceil(seconds / 60)) })}`;
  }
  return message;
}
