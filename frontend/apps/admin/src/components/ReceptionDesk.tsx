"use client";

import { ApiRequestError, newIdempotencyKey, type PriorityClass, type QueuedTicket, type QueueSnapshot, type SiteServices, type Ticket } from "@qms/api-client";
import { formatTokenNumber } from "@qms/i18n";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField } from "@qms/ui";
import { useCallback, useEffect, useRef, useState } from "react";
import { describeError, localisedName } from "../lib/admin-support";
import { useAuth } from "../lib/auth";
import { useApi } from "../lib/runtime";
import { TicketActionPanel } from "./TicketActionPanel";

/** Reasons the API gives a refused issue that this screen has a sentence for. */
const REFUSALS = new Set(["service_inactive", "channel_not_allowed", "appointment_only"]);

/**
 * The reception desk (SRS §8.3): choose a service, issue a walk-in ticket, read the token number, place and secret to the
 * visitor, and watch the service's queue. The screen only asks; the API checks the permission and the site
 * (FR-CFG-103). Each issuing action has one idempotency key that it keeps until the API has answered, so pressing the
 * button again after a lost response returns the same ticket instead of a second one (SRS §20.1). Reception may give
 * the ticket a Priority class (FR-QUE-011); leaving it on the default is the normal class. From the queue, reception may change
 * a waiting ticket's class with a reason (FR-QUE-012) or cancel a ticket (§5.2).
 */
export function ReceptionDesk() {
  const { t, language, formatNumber } = useI18n();
  const { client } = useApi();
  const { user } = useAuth();
  const siteId = user?.sites[0] ?? null;

  const [services, setServices] = useState<SiteServices | null>(null);
  const [servicesError, setServicesError] = useState<unknown>(null);
  const [selected, setSelected] = useState<string>("");
  const [classes, setClasses] = useState<PriorityClass[] | null>(null);
  const [classesError, setClassesError] = useState<unknown>(null);
  /** The chosen Priority class; empty is the default (normal) class, which is sent as no class at all. */
  const [priority, setPriority] = useState<string>("");
  const [queue, setQueue] = useState<QueueSnapshot | null>(null);
  const [queueError, setQueueError] = useState<unknown>(null);
  const [issued, setIssued] = useState<Ticket | null>(null);
  const [issuing, setIssuing] = useState(false);
  const [issueError, setIssueError] = useState<string | null>(null);
  const pending = useRef<{ request: string; key: string } | null>(null);
  /** The ticket in the queue an action is open for, and what it is. */
  const [acting, setActing] = useState<{ entry: QueuedTicket; mode: "priority" | "cancel" } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const loadServices = useCallback(async () => {
    if (!client || !siteId) return;
    try {
      setServices(await client.sites.services(siteId, "reception"));
      setServicesError(null);
    } catch (cause) {
      setServicesError(cause);
    }
  }, [client, siteId]);

  const loadQueue = useCallback(
    async (serviceId: string) => {
      if (!client || !serviceId) return;
      try {
        setQueue(await client.queues.snapshot(serviceId));
        setQueueError(null);
      } catch (cause) {
        setQueueError(cause);
      }
    },
    [client],
  );

  useEffect(() => {
    void loadServices();
  }, [loadServices]);

  useEffect(() => {
    if (!client) return;
    client.priority.classes().then(
      (result) => {
        setClasses(result.items);
        setClassesError(null);
      },
      (cause: unknown) => setClassesError(cause),
    );
  }, [client]);

  useEffect(() => {
    setQueue(null);
    setActing(null);
    setNotice(null);
    if (selected) void loadQueue(selected);
  }, [selected, loadQueue]);

  function openAction(entry: QueuedTicket, mode: "priority" | "cancel") {
    setNotice(null);
    setActing({ entry, mode });
  }

  async function actionDone(message: string) {
    setActing(null);
    setNotice(message);
    await Promise.all([loadServices(), loadQueue(selected)]);
  }

  async function issue() {
    if (!client || !selected) return;
    // A different service or class is a different request, so it gets its own key.
    const request = `${selected}|${priority}`;
    if (pending.current?.request !== request) pending.current = { request, key: newIdempotencyKey() };
    setIssuing(true);
    setIssueError(null);
    try {
      const ticket = await client.tickets.issue(
        {
          service_id: selected,
          origin_channel: "reception",
          occurred_at: new Date().toISOString(),
          ...(priority === "" ? {} : { priority_class_id: priority }),
        },
        pending.current.key,
      );
      pending.current = null;
      setIssued(ticket);
      await Promise.all([loadServices(), loadQueue(selected)]);
    } catch (cause) {
      // Only when the outcome is unknown (no answer, or a server fault) may the ticket exist, so the same key is kept.
      const unknown = cause instanceof ApiRequestError && (cause.code === "network_error" || cause.status >= 500);
      if (!unknown) pending.current = null;
      setIssueError(refusalText(cause));
    } finally {
      setIssuing(false);
    }
  }

  function refusalText(cause: unknown): string {
    const reason = cause instanceof ApiRequestError ? cause.body?.details?.reason : undefined;
    if (cause instanceof ApiRequestError && cause.code === "conflict" && typeof reason === "string" && REFUSALS.has(reason)) {
      return t(`reception.refused.${reason}`);
    }
    return describeError(t, cause);
  }

  if (user && !siteId) return <p className="qms-muted">{t("reception.noSite")}</p>;

  const defaultLanguage = services?.default_language ?? "";
  const nameOf = (names: Record<string, string>) => localisedName(names, language, defaultLanguage);
  const selectedService = services?.items.find((s) => s.id === selected) ?? null;
  const defaultClassId = classes?.find((c) => c.is_default)?.id;
  // The default class first and always available; the others only while active.
  const classOptions = (all: PriorityClass[]) => [
    ...all.filter((c) => c.is_default).map((c) => ({ value: "", label: nameOf(c.name_i18n) })),
    ...all
      .filter((c) => !c.is_default && c.active)
      .map((c) => ({ value: c.id, label: t("reception.priority.option", { name: nameOf(c.name_i18n), minutes: c.headstart_minutes }) })),
  ];

  return (
    <div className="qms-stack">
      <Card>
        <fieldset className="qms-stack">
          <legend className="qms-heading">{t("reception.services.title")}</legend>
          {servicesError !== null && <ErrorAlert>{describeError(t, servicesError)}</ErrorAlert>}
          {services?.items.length === 0 && <p className="qms-muted">{t("reception.services.none")}</p>}
          {services?.items.map((s) => (
            <label key={s.id} className="qms-row">
              <span>
                <input type="radio" name="reception-service" value={s.id} checked={selected === s.id} onChange={() => setSelected(s.id)} /> {nameOf(s.name_i18n)}
                <span className="qms-muted"> · {nameOf(s.service_group.name_i18n)}</span>
              </span>
              <span className="qms-muted">
                {s.estimated_wait_minutes
                  ? t("reception.services.waitingEstimate", {
                      count: s.waiting_count,
                      low: formatNumber(s.estimated_wait_minutes.low),
                      high: formatNumber(s.estimated_wait_minutes.high),
                    })
                  : t("reception.services.waiting", { count: s.waiting_count })}
              </span>
            </label>
          ))}
        </fieldset>
        {classesError !== null && <ErrorAlert>{describeError(t, classesError)}</ErrorAlert>}
        {classes && (
          <SelectField
            id="reception-priority"
            label={t("reception.priority.label")}
            value={priority}
            onChange={(event) => setPriority(event.target.value)}
            options={classOptions(classes)}
          />
        )}
        {issueError !== null && <ErrorAlert>{issueError}</ErrorAlert>}
        <Button type="button" disabled={!selected || issuing} onClick={issue}>
          {t(issuing ? "reception.issuing" : "reception.issue")}
        </Button>
      </Card>

      {issued && <IssuedTicket ticket={issued} nameOf={nameOf} />}

      <Card>
        {!selectedService && <p className="qms-muted">{t("reception.queue.pick")}</p>}
        {selectedService && (
          <>
            <div className="qms-row">
              <h2 className="qms-heading">{t("reception.queue.title", { service: nameOf(selectedService.name_i18n) })}</h2>
              <Button variant="secondary" type="button" onClick={() => void loadQueue(selected)}>
                {t("reception.queue.refresh")}
              </Button>
            </div>
            {queueError !== null && <ErrorAlert>{describeError(t, queueError)}</ErrorAlert>}
            {queue && (
              <>
                <p className="qms-muted">{t("reception.queue.count", { count: queue.waiting_count })}</p>
                {queue.estimated_wait_minutes && (
                  <p className="qms-muted">
                    {t("reception.queue.estimate", { low: formatNumber(queue.estimated_wait_minutes.low), high: formatNumber(queue.estimated_wait_minutes.high) })}
                  </p>
                )}
                {queue.tickets.length === 0 && <p className="qms-muted">{t("reception.queue.empty")}</p>}
                {queue.tickets.length > 0 && (
                  <ol className="qms-list" aria-label={t("reception.queue.title", { service: nameOf(selectedService.name_i18n) })}>
                    {queue.tickets.map((entry) => (
                      <li key={entry.id}>
                        <strong>{formatTokenNumber(entry.token_number)}</strong>
                        <span className="qms-muted">
                          {t(`reception.state.${entry.state}`)} · {t("reception.queue.position", { position: entry.position })}
                          {entry.priority_class && entry.priority_class.id !== defaultClassId && <> · {nameOf(entry.priority_class.name_i18n)}</>}
                        </span>
                        {entry.escalated && <span className="qms-warning">{t("reception.queue.escalated")}</span>}
                        <span className="qms-row">
                          {entry.state === "waiting" && (
                            <Button
                              variant="secondary"
                              type="button"
                              aria-label={t("reception.action.priorityOf", { token: formatTokenNumber(entry.token_number) })}
                              onClick={() => openAction(entry, "priority")}
                            >
                              {t("reception.action.priority")}
                            </Button>
                          )}
                          <Button
                            variant="secondary"
                            type="button"
                            aria-label={t("reception.action.cancelOf", { token: formatTokenNumber(entry.token_number) })}
                            onClick={() => openAction(entry, "cancel")}
                          >
                            {t("reception.action.cancel")}
                          </Button>
                        </span>
                      </li>
                    ))}
                  </ol>
                )}
              </>
            )}
            {notice !== null && (
              <p role="status" className="qms-muted">
                {notice}
              </p>
            )}
          </>
        )}
      </Card>

      {acting && classes && (
        <TicketActionPanel
          key={`${acting.mode}-${acting.entry.id}`}
          mode={acting.mode}
          entry={acting.entry}
          classes={classes}
          nameOf={nameOf}
          onClose={() => setActing(null)}
          onDone={(message) => void actionDone(message)}
        />
      )}
    </div>
  );
}

function IssuedTicket({ ticket, nameOf }: { ticket: Ticket; nameOf: (names: Record<string, string>) => string }) {
  const { t, formatNumber } = useI18n();
  const zone = ticket.zone;
  const estimate = ticket.estimated_wait_minutes;
  return (
    <Card>
      <h2 className="qms-heading">{t("reception.result.title")}</h2>
      <p className="qms-muted">{t("reception.result.token")}</p>
      <p className="qms-heading" data-testid="issued-token">
        {formatTokenNumber(ticket.token_number)}
      </p>
      <p>{t("reception.result.service", { service: nameOf(ticket.service.name_i18n) })}</p>
      {ticket.priority_class && <p>{t("reception.result.priority", { name: nameOf(ticket.priority_class.name_i18n) })}</p>}
      <p>
        {zone === null
          ? t("reception.result.noZone")
          : zone.building_label
            ? t("reception.result.zone", { zone: zone.name, building: zone.building_label, floor: zone.floor_label })
            : t("reception.result.zoneNoBuilding", { zone: zone.name, floor: zone.floor_label })}
      </p>
      {ticket.position !== null && <p>{t("reception.result.position", { position: ticket.position })}</p>}
      <p className="qms-muted">
        {estimate ? t("reception.result.estimate", { low: formatNumber(estimate.low), high: formatNumber(estimate.high) }) : t("reception.result.estimateNone")}
      </p>
      {ticket.secret && (
        <>
          <p>{t("reception.result.secret", { secret: ticket.secret })}</p>
          <p className="qms-muted">{t("reception.result.secretNote")}</p>
        </>
      )}
    </Card>
  );
}
