"use client";

import type { Notice, Site, Zone } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField } from "@qms/ui";
import { useId, useState } from "react";
import { describeError, useList } from "../lib/admin-support";
import { useApi } from "../lib/runtime";
import { EntityRow } from "./EntityRow";
import { NoticeForm } from "./NoticeForm";

/**
 * Notice-board content (ticket 30, FR-DSP-006, SRS §5.2 "Manage notice-board content", `notice_board:manage`):
 * schedule images, video or rich text for one Zone's `split_media` displays, in a playlist with per-item start and
 * end dates. The API enforces the permission and the Zone-scope check (FR-CFG-103); this screen only picks a Zone
 * to manage.
 */
export function NoticeBoardAdmin() {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const [siteId, setSiteId] = useState("");
  const [zoneId, setZoneId] = useState("");
  const [adding, setAdding] = useState(false);

  const sites = useList<Site>(client ? () => client.sites.list() : null, [client]);
  const zones = useList<Zone>(client && siteId ? () => client.sites.zones(siteId) : null, [client, siteId]);
  const notices = useList<Notice>(client && zoneId ? () => client.notices.forZone(zoneId) : null, [client, zoneId]);

  const site = sites.items?.find((s) => s.id === siteId);
  const languages = site?.enabled_languages ?? ["en"];
  const defaultLanguage = site?.default_language ?? "en";

  const noticeSummary = (n: Notice) =>
    t("noticeBoard.summary", {
      type: t(`noticeBoard.type.${n.type}`),
      starts: new Date(n.starts_at).toLocaleString(),
      ends: new Date(n.ends_at).toLocaleString(),
    });

  return (
    <div className="qms-stack">
      <p className="qms-muted">{t("noticeBoard.intro")}</p>
      <Card>
        <div className="qms-stack">
          <SelectField
            id={`${id}-site`}
            label={t("devices.fields.site")}
            value={siteId}
            onChange={(e) => {
              setSiteId(e.target.value);
              setZoneId("");
            }}
            options={[{ value: "", label: "" }, ...(sites.items ?? []).map((s) => ({ value: s.id, label: s.name }))]}
          />
          {siteId && (
            <SelectField
              id={`${id}-zone`}
              label={t("noticeBoard.fields.zone")}
              value={zoneId}
              onChange={(e) => setZoneId(e.target.value)}
              options={[{ value: "", label: "" }, ...(zones.items ?? []).map((z) => ({ value: z.id, label: z.name }))]}
            />
          )}
        </div>
      </Card>

      {!zoneId && <p className="qms-muted">{t("noticeBoard.selectZonePrompt")}</p>}

      {zoneId && (
        <Card>
          <h2 className="qms-heading">{t("noticeBoard.list.title")}</h2>
          {notices.error !== null && <ErrorAlert>{describeError(t, notices.error)}</ErrorAlert>}
          {notices.items?.length === 0 && <p className="qms-muted">{t("noticeBoard.list.empty")}</p>}
          {notices.items && notices.items.length > 0 && (
            <ul className="qms-list">
              {notices.items.map((n) => (
                <EntityRow
                  key={n.id}
                  name={noticeSummary(n)}
                  heading={noticeSummary(n)}
                  lines={[]}
                  active={n.active}
                  confirmText={t("noticeBoard.confirmDeactivate")}
                  onDeactivate={async () => {
                    await client!.notices.deactivate(n.id);
                    notices.reload();
                  }}
                  onActivate={async () => {
                    await client!.notices.activate(n.id);
                    notices.reload();
                  }}
                  editForm={(close) => (
                    <NoticeForm
                      zoneId={zoneId}
                      languages={languages}
                      defaultLanguage={defaultLanguage}
                      initial={n}
                      submitLabel={t("admin.action.save")}
                      onSubmit={(input) => client!.notices.update(n.id, input)}
                      onDone={() => {
                        close();
                        notices.reload();
                      }}
                      onCancel={close}
                    />
                  )}
                />
              ))}
            </ul>
          )}
          {adding ? (
            <NoticeForm
              zoneId={zoneId}
              languages={languages}
              defaultLanguage={defaultLanguage}
              submitLabel={t("noticeBoard.create")}
              onSubmit={(input) => client!.notices.create(input)}
              onDone={() => {
                setAdding(false);
                notices.reload();
              }}
              onCancel={() => setAdding(false)}
            />
          ) : (
            <Button variant="secondary" type="button" onClick={() => setAdding(true)}>
              {t("noticeBoard.add")}
            </Button>
          )}
        </Card>
      )}
    </div>
  );
}
