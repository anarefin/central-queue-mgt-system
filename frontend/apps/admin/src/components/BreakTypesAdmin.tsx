"use client";

import type { BreakType } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert } from "@qms/ui";
import { useState } from "react";
import { describeError, useList } from "../lib/admin-support";
import { useApi } from "../lib/runtime";
import { BreakTypeForm } from "./BreakTypeForm";
import { EntityRow } from "./EntityRow";

/**
 * Break types (FR-AGT-020): what an agent may pick when they press F9, each with names in every language and an optional longest
 * duration. A type is deactivated, never deleted, so a break already taken keeps it. The API checks the permission (FR-CFG-103).
 */
export function BreakTypesAdmin() {
  const { t, language } = useI18n();
  const { client } = useApi();
  const [adding, setAdding] = useState(false);
  const types = useList<BreakType>(client ? () => client.breaks.types() : null, [client]);

  const nameOf = (b: BreakType) => b.name_i18n[language] ?? b.name_i18n.en ?? Object.values(b.name_i18n)[0] ?? "";

  return (
    <div className="qms-stack">
      <p className="qms-muted">{t("breaks.intro")}</p>
      <Card>
        <h2 className="qms-heading">{t("breaks.title")}</h2>
        {types.error !== null && <ErrorAlert>{describeError(t, types.error)}</ErrorAlert>}
        {types.items?.length === 0 && <p className="qms-muted">{t("breaks.none")}</p>}
        {types.items && types.items.length > 0 && (
          <ul className="qms-list">
            {types.items.map((b) => (
              <EntityRow
                key={b.id}
                name={nameOf(b)}
                heading={nameOf(b)}
                lines={[t("breaks.summary", { max: b.max_minutes === null ? t("breaks.noLimit") : t("breaks.max", { minutes: b.max_minutes }) })]}
                active={b.active}
                confirmText={t("breaks.confirmDeactivate", { name: nameOf(b) })}
                onDeactivate={async () => {
                  await client!.breaks.deactivateType(b.id);
                  types.reload();
                }}
                onActivate={async () => {
                  await client!.breaks.activateType(b.id);
                  types.reload();
                }}
                editForm={(close) => (
                  <BreakTypeForm
                    initial={b}
                    submitLabel={t("admin.action.save")}
                    onSubmit={(input) => client!.breaks.updateType(b.id, input)}
                    onDone={() => {
                      close();
                      types.reload();
                    }}
                    onCancel={close}
                  />
                )}
              />
            ))}
          </ul>
        )}
        {adding ? (
          <BreakTypeForm
            submitLabel={t("breaks.create")}
            onSubmit={(input) => client!.breaks.createType(input)}
            onDone={() => {
              setAdding(false);
              types.reload();
            }}
            onCancel={() => setAdding(false)}
          />
        ) : (
          <Button variant="secondary" type="button" onClick={() => setAdding(true)}>
            {t("breaks.add")}
          </Button>
        )}
      </Card>
    </div>
  );
}
