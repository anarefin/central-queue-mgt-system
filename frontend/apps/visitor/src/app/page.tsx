"use client";

import { useI18n } from "@qms/i18n/react";
import { Card, ErrorAlert, Page } from "@qms/ui";
import { useEffect, useState } from "react";
import { VisitorTicketStatus } from "../components/VisitorTicketStatus";
import { BASE_PATH } from "../lib/runtime";

interface TicketReference {
  ticketId: string;
  credential: string;
}

/**
 * The `t` query parameter and `s` URL fragment the kiosk's printer-failure QR (and every other visitor entry point)
 * puts in this page's URL (FR-ISS-016, ticket 25/27's own contract; ADR-0012: a static export cannot route on a
 * dynamic segment, so the reference rides the URL instead of a path). The Ticket id is not sensitive on its own
 * (FR-SEC-033) and rides the query string; the secret rides the fragment instead (`#s=`), which a browser never
 * sends to any server (so it never lands in a proxy's own access log, API-018) and always strips from the `Referer`
 * it gives another origin. The fragment is then stripped from the visible URL entirely (`history.replaceState`), so
 * it is not retained in `document.location`, in browser history, or readable by a later-loaded third-party script
 * (such as an admin-configured wayfinding or branding image, ticket 27/37).
 */
function useTicketReference(): TicketReference | null | undefined {
  const [reference, setReference] = useState<TicketReference | null | undefined>(undefined);
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const ticketId = params.get("t");
    const credential = new URLSearchParams(window.location.hash.replace(/^#/, "")).get("s");
    if (credential) {
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
    }
    setReference(ticketId && credential ? { ticketId, credential } : null);
  }, []);
  return reference;
}

export default function Home() {
  const { t } = useI18n();
  const reference = useTicketReference();

  if (reference === undefined) return null;
  if (reference === null) {
    return (
      <Page>
        <Card>
          <h1 className="text-2xl font-semibold text-fg">{t("app.visitor")}</h1>
          <ErrorAlert>{t("visitor.missingReference")}</ErrorAlert>
          <a className="text-primary underline" href={`${BASE_PATH}/account/`}>
            {t("visitor.accountLink")}
          </a>
        </Card>
      </Page>
    );
  }
  return <VisitorTicketStatus ticketId={reference.ticketId} credential={reference.credential} />;
}
