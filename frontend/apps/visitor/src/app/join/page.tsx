"use client";

import { useI18n } from "@qms/i18n/react";
import { Card, ErrorAlert, Page } from "@qms/ui";
import { useEffect, useState } from "react";
import { RemoteJoin } from "../../components/RemoteJoin";

/** The `service` query parameter every remote-join entry point (a site's own QR or link) carries — the same "ride the
 * URL, a static export cannot route on a dynamic segment" shape the home page's own ticket reference already is
 * (ticket 37, ADR-0012). Unlike the ticket reference, a Service id is not a secret, so it rides the query string
 * plainly rather than the fragment. */
function useServiceId(): string | null | undefined {
  const [serviceId, setServiceId] = useState<string | null | undefined>(undefined);
  useEffect(() => {
    setServiceId(new URLSearchParams(window.location.search).get("service"));
  }, []);
  return serviceId;
}

export default function JoinPage() {
  const { t } = useI18n();
  const serviceId = useServiceId();

  if (serviceId === undefined) return null;
  if (!serviceId) {
    return (
      <Page>
        <Card>
          <h1 className="text-2xl font-semibold text-fg">{t("remoteJoin.title")}</h1>
          <ErrorAlert>{t("remoteJoin.missingService")}</ErrorAlert>
        </Card>
      </Page>
    );
  }
  return <RemoteJoin serviceId={serviceId} />;
}
