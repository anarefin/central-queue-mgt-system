"use client";

import { ApiRequestError } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, TextField } from "@qms/ui";
import { useState, type FormEvent } from "react";
import { useAccount } from "../lib/visitorAuth";

/** Email + OTP sign-in (ticket 41, FR-MOB-001): two steps, request then verify, each its own form so a mistyped
 * email never has to be re-typed alongside a code. */
export function VisitorLogin() {
  const { t } = useI18n();
  const { requestOtp, verifyOtp } = useAccount();
  const [step, setStep] = useState<"email" | "code">("email");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submitEmail(event: FormEvent) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      await requestOtp(email.trim());
      setStep("code");
    } catch (cause) {
      setError(describe(cause, t));
    } finally {
      setPending(false);
    }
  }

  async function submitCode(event: FormEvent) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      await verifyOtp(email.trim(), code.trim());
      setCode("");
    } catch (cause) {
      setError(describe(cause, t));
    } finally {
      setPending(false);
    }
  }

  async function resend() {
    setPending(true);
    setError(null);
    try {
      await requestOtp(email.trim());
    } catch (cause) {
      setError(describe(cause, t));
    } finally {
      setPending(false);
    }
  }

  if (step === "email") {
    return (
      <Card>
        <h1 className="qms-heading">{t("account.login.title")}</h1>
        <p className="qms-muted">{t("account.login.intro")}</p>
        <form className="qms-stack" onSubmit={submitEmail}>
          <TextField
            id="visitor-email"
            label={t("account.login.email")}
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="email"
            autoCapitalize="none"
            spellCheck={false}
            required
          />
          {error && <ErrorAlert>{error}</ErrorAlert>}
          <Button type="submit" disabled={pending || !email.trim()}>
            {pending ? t("account.login.sending") : t("account.login.requestCode")}
          </Button>
        </form>
      </Card>
    );
  }

  return (
    <Card>
      <h1 className="qms-heading">{t("account.login.title")}</h1>
      <p className="qms-muted">{t("account.login.codeSentIntro", { email })}</p>
      <form className="qms-stack" onSubmit={submitCode}>
        <TextField
          id="visitor-otp"
          label={t("account.login.code")}
          value={code}
          onChange={(e) => setCode(e.target.value)}
          inputMode="numeric"
          autoComplete="one-time-code"
          required
        />
        {error && <ErrorAlert>{error}</ErrorAlert>}
        <Button type="submit" disabled={pending || !code.trim()}>
          {pending ? t("account.login.verifying") : t("account.login.verify")}
        </Button>
        <Button type="button" variant="secondary" onClick={() => void resend()} disabled={pending}>
          {t("account.login.resend")}
        </Button>
        <Button type="button" variant="secondary" onClick={() => setStep("email")} disabled={pending}>
          {t("account.login.changeEmail")}
        </Button>
      </form>
    </Card>
  );
}

/** A localised message for a failed request or verify; the code, not the server's text, chooses it (SRS §20.3). */
function describe(cause: unknown, t: (key: string, params?: Record<string, string | number>) => string): string {
  if (!(cause instanceof ApiRequestError)) return t("errors.network_error");
  // A wrong or expired OTP is `invalid_credentials`, the same code staff's own bad-password answer uses, but that
  // wording ("username or password") makes no sense to a visitor entering a one-time code.
  if (cause.code === "invalid_credentials") return t("account.login.wrongCode");
  const message = t(`errors.${cause.code}`);
  const seconds = cause.body?.details?.retry_after_seconds;
  if (cause.code === "rate_limited" && typeof seconds === "number") {
    return `${message} ${t("auth.retryIn", { minutes: Math.max(1, Math.ceil(seconds / 60)) })}`;
  }
  return message;
}
