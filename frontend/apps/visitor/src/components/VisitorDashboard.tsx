"use client";

import { ApiRequestError, type AppointmentSummary, type SavedSite, type TicketSummary } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, TextField } from "@qms/ui";
import { useCallback, useEffect, useState } from "react";
import { useApi } from "../lib/runtime";
import { useAccount } from "../lib/visitorAuth";

const CANCELLABLE_APPOINTMENT_STATES = new Set(["booked", "rescheduled"]);

function localisedName(names: Record<string, string>, language: string): string {
  return names[language] ?? names.en ?? Object.values(names)[0] ?? "";
}

/** A registered visitor's own "my account" screen (ticket 41, FR-MOB-002): active tickets, appointment history and
 * saved sites, with self-service reschedule and cancel on a booked appointment (FR-APT-020, §5.2). */
export function VisitorDashboard() {
  const { t, language } = useI18n();
  const { client } = useApi();
  const { me, logout } = useAccount();

  const [tickets, setTickets] = useState<TicketSummary[] | null>(null);
  const [appointments, setAppointments] = useState<AppointmentSummary[] | null>(null);
  const [sites, setSites] = useState<SavedSite[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rescheduling, setRescheduling] = useState<string | null>(null);

  const reload = useCallback(async () => {
    if (!client) return;
    try {
      const [t1, t2, t3] = await Promise.all([client.visitorAccount.tickets(), client.visitorAccount.appointments(), client.visitorAccount.savedSites()]);
      setTickets(t1.items);
      setAppointments(t2.items);
      setSites(t3.items);
    } catch {
      setError(t("account.error.generic"));
    }
  }, [client, t]);

  useEffect(() => {
    void reload();
  }, [reload]);

  async function cancelAppointment(id: string) {
    if (!client) return;
    try {
      await client.appointments.cancel(id);
      await reload();
    } catch {
      setError(t("account.error.generic"));
    }
  }

  async function removeSite(siteId: string) {
    if (!client) return;
    try {
      await client.visitorAccount.unsaveSite(siteId);
      await reload();
    } catch {
      setError(t("account.error.generic"));
    }
  }

  return (
    <>
      <Card>
        <h1 className="text-2xl font-semibold text-fg">{t("account.title")}</h1>
        {me && <p className="text-fg-muted">{t("account.signedInAs", { email: me.email })}</p>}
        {error && <ErrorAlert>{error}</ErrorAlert>}
        <Button className="w-full" variant="secondary" onClick={() => void logout()}>
          {t("account.signOut")}
        </Button>
      </Card>

      <Card>
        <h2 className="text-lg font-semibold text-fg">{t("account.tickets.title")}</h2>
        {tickets && tickets.length === 0 && <p className="text-fg-muted">{t("account.tickets.empty")}</p>}
        {tickets?.map((ticket) => (
          <p key={ticket.id}>
            {ticket.token_number} · {localisedName(ticket.service_names, language)} · {ticket.site_name} · {t(`visitor.state.${ticket.state}`)}
          </p>
        ))}
      </Card>

      <Card>
        <h2 className="text-lg font-semibold text-fg">{t("account.appointments.title")}</h2>
        {appointments && appointments.length === 0 && <p className="text-fg-muted">{t("account.appointments.empty")}</p>}
        {appointments?.map((appointment) => (
          <div key={appointment.id} className="flex flex-col gap-4">
            <p>
              {appointment.reference_code} · {localisedName(appointment.service_names, language)} · {appointment.site_name} · {appointment.date} {appointment.start}
              –{appointment.end} · {appointment.state}
            </p>
            {CANCELLABLE_APPOINTMENT_STATES.has(appointment.state) && (
              <div className="flex flex-col gap-2">
                <Button className="w-full" variant="secondary" onClick={() => setRescheduling(rescheduling === appointment.id ? null : appointment.id)}>
                  {t("account.appointments.reschedule")}
                </Button>
                <Button className="w-full" variant="secondary" onClick={() => void cancelAppointment(appointment.id)}>
                  {t("account.appointments.cancel")}
                </Button>
                {rescheduling === appointment.id && (
                  <RescheduleForm
                    appointmentId={appointment.id}
                    onDone={() => {
                      setRescheduling(null);
                      void reload();
                    }}
                    onCancel={() => setRescheduling(null)}
                  />
                )}
              </div>
            )}
          </div>
        ))}
      </Card>

      <Card>
        <h2 className="text-lg font-semibold text-fg">{t("account.sites.title")}</h2>
        {sites && sites.length === 0 && <p className="text-fg-muted">{t("account.sites.empty")}</p>}
        {sites?.map((site) => (
          <div key={site.site_id} className="flex items-center justify-between gap-2">
            <p>{site.site_name}</p>
            <Button variant="secondary" onClick={() => void removeSite(site.site_id)}>
              {t("account.sites.remove")}
            </Button>
          </div>
        ))}
      </Card>
    </>
  );
}

function RescheduleForm({ appointmentId, onDone, onCancel }: { appointmentId: string; onDone: () => void; onCancel: () => void }) {
  const { t } = useI18n();
  const { client } = useApi();
  const [date, setDate] = useState("");
  const [start, setStart] = useState("");
  const [end, setEnd] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!client) return;
    setPending(true);
    setError(null);
    try {
      await client.appointments.reschedule(appointmentId, { date, start, end });
      onDone();
    } catch (cause) {
      setError(cause instanceof ApiRequestError ? t(`errors.${cause.code}`) : t("errors.network_error"));
    } finally {
      setPending(false);
    }
  }

  return (
    <Card>
      <h3 className="text-lg font-semibold text-fg">{t("account.appointments.rescheduleTitle")}</h3>
      <TextField id={`reschedule-date-${appointmentId}`} label={t("account.appointments.date")} type="date" value={date} onChange={(e) => setDate(e.target.value)} required />
      <TextField
        id={`reschedule-start-${appointmentId}`}
        label={t("account.appointments.start")}
        type="time"
        value={start}
        onChange={(e) => setStart(e.target.value)}
        required
      />
      <TextField id={`reschedule-end-${appointmentId}`} label={t("account.appointments.end")} type="time" value={end} onChange={(e) => setEnd(e.target.value)} required />
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <Button className="w-full" onClick={() => void submit()} disabled={pending || !date || !start || !end}>
        {pending ? t("account.appointments.saving") : t("account.appointments.save")}
      </Button>
      <Button className="w-full" variant="secondary" onClick={onCancel} disabled={pending}>
        {t("account.appointments.close")}
      </Button>
    </Card>
  );
}
