"use client";

import type { PriorityClass } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert } from "@qms/ui";
import { useState } from "react";
import { describeError, useList } from "../lib/admin-support";
import { useApi } from "../lib/runtime";
import { EntityRow } from "./EntityRow";
import { PriorityClassForm } from "./PriorityClassForm";

/** The Priority classes (FR-QUE-010): the default normal class first, then the others by Head start. */
export function PriorityClassesCard() {
  const { t, language } = useI18n();
  const { client } = useApi();
  const [adding, setAdding] = useState(false);
  const classes = useList<PriorityClass>(client ? () => client.priority.classes() : null, [client]);

  const nameOf = (c: PriorityClass) => c.name_i18n[language] ?? c.name_i18n.en ?? Object.values(c.name_i18n)[0] ?? "";
  const none = t("priority.classes.none");

  return (
    <Card>
      <h2 className="qms-heading">{t("priority.classes.title")}</h2>
      <p className="qms-muted">{t("priority.classes.intro")}</p>
      {classes.error !== null && <ErrorAlert>{describeError(t, classes.error)}</ErrorAlert>}
      {classes.items && (
        <ul className="qms-list">
          {classes.items.map((c) => (
            <EntityRow
              key={c.id}
              name={nameOf(c)}
              heading={nameOf(c)}
              lines={[
                t("priority.classes.summary", {
                  headstart: c.headstart_minutes,
                  maxWait: c.max_wait_minutes === null ? none : t("priority.classes.maxWait", { minutes: c.max_wait_minutes }),
                  prefix: c.token_prefix_override ?? none,
                }),
                ...(c.is_default ? [t("priority.classes.defaultNote")] : []),
              ]}
              active={c.active}
              canDeactivate={!c.is_default}
              confirmText={t("priority.classes.confirmDeactivate", { name: nameOf(c) })}
              onDeactivate={async () => {
                await client!.priority.deactivateClass(c.id);
                classes.reload();
              }}
              onActivate={async () => {
                await client!.priority.activateClass(c.id);
                classes.reload();
              }}
              editForm={(close) => (
                <PriorityClassForm
                  initial={c}
                  submitLabel={t("admin.action.save")}
                  onSubmit={(input) => client!.priority.updateClass(c.id, input)}
                  onDone={() => {
                    close();
                    classes.reload();
                  }}
                  onCancel={close}
                />
              )}
            />
          ))}
        </ul>
      )}
      {adding ? (
        <PriorityClassForm
          submitLabel={t("priority.classes.create")}
          onSubmit={(input) => client!.priority.createClass(input)}
          onDone={() => {
            setAdding(false);
            classes.reload();
          }}
          onCancel={() => setAdding(false)}
        />
      ) : (
        <Button variant="secondary" type="button" onClick={() => setAdding(true)}>
          {t("priority.classes.add")}
        </Button>
      )}
    </Card>
  );
}
