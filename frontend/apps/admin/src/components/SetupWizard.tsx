"use client";

import type { SetupState, VerticalProfile } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField, StatusBadge, TextField } from "@qms/ui";
import Link from "next/link";
import { useEffect, useId, useState, type FormEvent, type ReactNode } from "react";
import { describeError, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

/**
 * The first-run setup wizard (SRS §26.2, FR-OPS-010, ticket 56): pick a vertical profile, then walk through the
 * rest of §26.2's steps (each one a link to the admin screen ticket 05/06/08/24 already built), then prove the
 * system end to end with one real, wizard-issued test token before go-live unlocks.
 */
export function SetupWizard() {
  const { t } = useI18n();
  const { client } = useApi();
  const [state, setState] = useState<SetupState | null>(null);
  const [profiles, setProfiles] = useState<VerticalProfile[] | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    Promise.all([client.setup.state(), client.setup.profiles()]).then(
      ([s, p]) => {
        if (!cancelled) {
          setState(s);
          setProfiles(p);
        }
      },
      (cause: unknown) => !cancelled && setLoadError(cause),
    );
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, version]);

  const refresh = () => setVersion((v) => v + 1);

  if (loadError !== null) return <ErrorAlert>{describeError(t, loadError)}</ErrorAlert>;
  if (!state || !profiles) return <p className="qms-muted">{t("setup.profile.loading")}</p>;

  return (
    <div className="qms-stack">
      <p className="qms-muted">{t("setup.intro")}</p>
      <ProfileCard state={state} profiles={profiles} onChanged={refresh} />
      <StepCard done={state.org_and_sites} label={t("setup.step.orgAndSites")}>
        <Link href="/sites/">{t("setup.link.openSites")}</Link>
      </StepCard>
      <StepCard done={state.zones_and_counters} label={t("setup.step.zonesAndCounters")}>
        <Link href="/sites/">{t("setup.link.openSites")}</Link>
      </StepCard>
      <StepCard done={state.services_and_numbering} label={t("setup.step.servicesAndNumbering")}>
        <Link href="/catalogue/">{t("setup.link.openCatalogue")}</Link>
        <Link href="/numbering/">{t("setup.link.openNumbering")}</Link>
      </StepCard>
      <StepCard done={state.users_and_roles} label={t("setup.step.usersAndRoles")}>
        <span className="qms-muted">{t("setup.usersHint")}</span>
      </StepCard>
      <StepCard done={state.devices_registered} label={t("setup.step.devices")}>
        <Link href="/devices/">{t("setup.link.openDevices")}</Link>
      </StepCard>
      <TestTokenCard state={state} onChanged={refresh} />
      <GoLiveCard state={state} onChanged={refresh} />
    </div>
  );
}

function StepCard({ done, label, children }: { done: boolean; label: string; children?: ReactNode }) {
  const { t } = useI18n();
  return (
    <Card>
      <div className="qms-row">
        <h2 className="qms-heading">{label}</h2>
        <StatusBadge status={done ? "up" : "not_configured"}>{t(done ? "setup.status.done" : "setup.status.pending")}</StatusBadge>
      </div>
      {children && <div className="qms-row">{children}</div>}
    </Card>
  );
}

function profileName(t: (key: string) => string, id: string): string {
  return t(`setup.profile.name.${id}`);
}

function ProfileCard({ state, profiles, onChanged }: { state: SetupState; profiles: VerticalProfile[]; onChanged: () => void }) {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const [selected, setSelected] = useState(profiles[0]?.id ?? "");
  const [showChooser, setShowChooser] = useState(!state.profile_applied);
  const { busy, error, run } = useSubmit();

  async function apply(event: FormEvent) {
    event.preventDefault();
    const ok = await run(async () => {
      if (state.profile_applied) await client!.setup.resetProfile(selected);
      else await client!.setup.applyProfile(selected);
    });
    if (ok) {
      setShowChooser(false);
      onChanged();
    }
  }

  return (
    <Card>
      <h2 className="qms-heading">{t("setup.step.profile")}</h2>
      {state.active_profile && (
        <div className="qms-row">
          <StatusBadge status="up">{t("setup.status.done")}</StatusBadge>
          <span>{t("setup.profile.applied", { profile: profileName(t, state.active_profile.id) })}</span>
          <Button variant="secondary" type="button" onClick={() => setShowChooser((v) => !v)}>
            {t("setup.profile.chooseAnother")}
          </Button>
        </div>
      )}
      {(showChooser || !state.profile_applied) && (
        <form className="qms-stack" onSubmit={apply}>
          <SelectField
            id={`${id}-profile`}
            label={t("setup.step.profile")}
            value={selected}
            onChange={(e) => setSelected(e.target.value)}
            options={profiles.map((p) => ({ value: p.id, label: profileName(t, p.id) }))}
          />
          {error && <ErrorAlert>{error}</ErrorAlert>}
          <div className="qms-row">
            <Button type="submit" disabled={busy}>
              {t(state.profile_applied ? "setup.profile.resetButton" : "setup.profile.applyButton")}
            </Button>
          </div>
        </form>
      )}
    </Card>
  );
}

function TestTokenCard({ state, onChanged }: { state: SetupState; onChanged: () => void }) {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const [serviceId, setServiceId] = useState("");
  const { busy, error, run } = useSubmit();
  const token = state.test_token;

  async function issue(event: FormEvent) {
    event.preventDefault();
    const ok = await run(async () => {
      await client!.setup.issueTestToken(serviceId.trim());
    });
    if (ok) onChanged();
  }

  async function confirmPrint() {
    if (!token.ticket_id) return;
    const ok = await run(async () => {
      await client!.setup.confirmPrint(token.ticket_id!);
    });
    if (ok) onChanged();
  }

  async function confirmAnnounce() {
    if (!token.ticket_id) return;
    const ok = await run(async () => {
      await client!.setup.confirmAnnounce(token.ticket_id!);
    });
    if (ok) onChanged();
  }

  return (
    <Card>
      <h2 className="qms-heading">{t("setup.step.testToken")}</h2>
      <p className="qms-muted">{t("setup.testToken.intro")}</p>
      <div className="qms-row">
        <TestStatus done={token.issued} label={t("setup.testToken.status.issued")} />
        <TestStatus done={token.printed} label={t("setup.testToken.status.printed")} />
        <TestStatus done={token.called} label={t("setup.testToken.status.called")} />
        <TestStatus done={token.announced} label={t("setup.testToken.status.announced")} />
      </div>
      {!token.issued && (
        <form className="qms-stack" onSubmit={issue}>
          <TextField id={`${id}-service`} label={t("setup.testToken.serviceLabel")} value={serviceId} onChange={(e) => setServiceId(e.target.value)} required />
          {error && <ErrorAlert>{error}</ErrorAlert>}
          <div className="qms-row">
            <Button type="submit" disabled={busy || serviceId.trim() === ""}>
              {t("setup.testToken.issueButton")}
            </Button>
          </div>
        </form>
      )}
      {token.issued && (
        <div className="qms-stack">
          <p>{t("setup.testToken.issued", { token: token.token_number ?? "" })}</p>
          {error && <ErrorAlert>{error}</ErrorAlert>}
          {!token.printed && (
            <div className="qms-row">
              <Button type="button" onClick={confirmPrint} disabled={busy}>
                {t("setup.testToken.confirmPrintButton")}
              </Button>
            </div>
          )}
          {token.printed && !token.called && <p className="qms-muted">{t("setup.testToken.waitingForCall")}</p>}
          {token.called && !token.announced && (
            <div className="qms-row">
              <Button type="button" onClick={confirmAnnounce} disabled={busy}>
                {t("setup.testToken.confirmAnnounceButton")}
              </Button>
            </div>
          )}
          <div className="qms-row">
            <Button variant="secondary" type="button" onClick={onChanged}>
              {t("setup.testToken.refreshButton")}
            </Button>
          </div>
        </div>
      )}
    </Card>
  );
}

function TestStatus({ done, label }: { done: boolean; label: string }) {
  return <StatusBadge status={done ? "up" : "not_configured"}>{label}</StatusBadge>;
}

function GoLiveCard({ state, onChanged }: { state: SetupState; onChanged: () => void }) {
  const { t } = useI18n();
  const { client } = useApi();
  const { busy, error, run } = useSubmit();

  async function goLive() {
    const ok = await run(async () => {
      await client!.setup.goLive();
    });
    if (ok) onChanged();
  }

  return (
    <Card>
      <h2 className="qms-heading">{t("setup.step.goLive")}</h2>
      {state.go_live_at ? (
        <p>{t("setup.goLive.done", { at: new Date(state.go_live_at).toLocaleString() })}</p>
      ) : (
        <div className="qms-stack">
          {!state.go_live_ready && <p className="qms-muted">{t("setup.goLive.blocked")}</p>}
          {error && <ErrorAlert>{error}</ErrorAlert>}
          <div className="qms-row">
            <Button type="button" onClick={goLive} disabled={busy || !state.go_live_ready}>
              {t("setup.goLive.button")}
            </Button>
          </div>
        </div>
      )}
    </Card>
  );
}
