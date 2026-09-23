"use client";

import type { ServiceGroup, Team, UserSummary } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { describeError, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

/**
 * The one team of a service group and who is in it. An Organisation Admin changes it directly; a Team Admin's change is
 * an approval request that takes effect only once approved (FR-CFG-102), so this screen never offers that path.
 */
export function TeamCard({ group, groupName }: { group: ServiceGroup; groupName: string }) {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const team = useList<Team>(client ? () => client.catalogue.team(group.id).then((t) => ({ items: [t] })) : null, [client, group.id]);
  const users = useList<UserSummary>(client ? () => client.users.list() : null, [client]);
  const [userId, setUserId] = useState("");
  const { busy, error, run } = useSubmit();
  const rowError = useSubmit();

  const members = team.items?.[0]?.members ?? [];
  const member = new Set(members.map((m) => m.user_id));
  const candidates = users.items?.filter((u) => u.active && !member.has(u.id)) ?? [];

  async function add(event: FormEvent) {
    event.preventDefault();
    if (await run(() => client!.catalogue.addMember(group.id, userId))) {
      setUserId("");
      team.reload();
    }
  }

  return (
    <Card>
      <h2 className="font-semibold text-fg">{t("catalogue.team.title", { group: groupName })}</h2>
      <p className="text-fg-muted">{t("catalogue.team.hint")}</p>
      {team.error !== null && <ErrorAlert>{describeError(t, team.error)}</ErrorAlert>}
      {team.items && members.length === 0 && <p className="text-fg-muted">{t("catalogue.team.empty")}</p>}
      {members.length > 0 && (
        <ul className="m-0 list-none p-0 flex flex-col divide-y divide-border [&>li]:flex [&>li]:flex-wrap [&>li]:items-center [&>li]:justify-between [&>li]:gap-2 [&>li]:py-2.5">
          {members.map((m) => {
            const label = m.display_name ?? m.username;
            return (
              <li key={m.user_id}>
                <div className="flex flex-wrap items-center justify-between gap-3 flex-1 min-w-0">
                  <span>
                    {label}
                    {!m.active && ` ${t("catalogue.team.disabled")}`}
                  </span>
                  <Button
                    variant="secondary"
                    type="button"
                    aria-label={`${t("catalogue.team.remove")} ${label}`}
                    disabled={rowError.busy}
                    onClick={async () => {
                      if (await rowError.run(() => client!.catalogue.removeMember(group.id, m.user_id))) team.reload();
                    }}
                  >
                    {t("catalogue.team.remove")}
                  </Button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
      {rowError.error && <ErrorAlert>{rowError.error}</ErrorAlert>}
      <h3 className="font-semibold text-fg">{t("catalogue.team.add")}</h3>
      <form className="flex flex-col gap-4" onSubmit={add}>
        {users.error !== null && <ErrorAlert>{describeError(t, users.error)}</ErrorAlert>}
        <SelectField
          id={`${id}-user`}
          label={t("catalogue.fields.user_id")}
          value={userId}
          onChange={(event) => setUserId(event.target.value)}
          options={[
            { value: "", label: t("catalogue.team.choose") },
            ...candidates.map((u) => ({ value: u.id, label: u.display_name ?? u.username })),
          ]}
        />
        {error && <ErrorAlert>{error}</ErrorAlert>}
        <Button type="submit" disabled={busy || userId === ""}>
          {t("catalogue.team.add")}
        </Button>
      </form>
    </Card>
  );
}
