"use client";

import {
  ApiRequestError,
  newIdempotencyKey,
  type JourneyResult,
  type JourneyTemplateSummary,
  type PriorityClass,
  type QueuedTicket,
  type QueueSnapshot,
  type SiteServiceItem,
  type SiteServices,
  type Ticket,
  type VisitorMatch,
} from "@qms/api-client";
import { formatTokenNumber } from "@qms/i18n";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField, TextField } from "@qms/ui";
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
  /** The visitor this ticket is for (FR-ISS-020): from a directory search or a fresh walk-in registration. */
  const [visitor, setVisitor] = useState<{ id: string; name: string | null } | null>(null);
  /** The note visible only to the agent who serves this ticket (FR-ISS-020). */
  const [note, setNote] = useState("");
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
    const trimmedNote = note.trim();
    // A different service, class, visitor or note is a different request, so it gets its own key.
    const request = `${selected}|${priority}|${visitor?.id ?? ""}|${trimmedNote}`;
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
          ...(visitor === null ? {} : { visitor_id: visitor.id }),
          ...(trimmedNote === "" ? {} : { purpose_note: trimmedNote }),
        },
        pending.current.key,
      );
      pending.current = null;
      setIssued(ticket);
      setVisitor(null);
      setNote("");
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
        <VisitorPanel visitor={visitor} onSelect={setVisitor} onClear={() => setVisitor(null)} />
        <TextField id="reception-note" label={t("reception.note.label")} value={note} maxLength={1000} onChange={(event) => setNote(event.target.value)} />
        {issueError !== null && <ErrorAlert>{issueError}</ErrorAlert>}
        <Button type="button" disabled={!selected || issuing} onClick={issue}>
          {t(issuing ? "reception.issuing" : "reception.issue")}
        </Button>
      </Card>

      {issued && <IssuedTicket ticket={issued} nameOf={nameOf} />}

      {siteId && services && <JourneySection siteId={siteId} services={services.items} classes={classes ?? []} nameOf={nameOf} />}

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

/**
 * The visitor an issued ticket is for (FR-ISS-020): search the directory by code, phone or QR (FR-ISS-020,
 * FR-INT-010), or register an unknown walk-in with a minimal record and a pass reference (FR-ISS-021). Selecting a
 * result, or registering one, names the visitor the surrounding form's Issue action carries as `visitor_id`.
 */
function VisitorPanel({
  visitor,
  onSelect,
  onClear,
}: {
  visitor: { id: string; name: string | null } | null;
  onSelect: (visitor: { id: string; name: string | null }) => void;
  onClear: () => void;
}) {
  const { t } = useI18n();
  const { client } = useApi();
  const [query, setQuery] = useState("");
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [found, setFound] = useState<VisitorMatch | null>(null);
  const [registering, setRegistering] = useState(false);
  const [regName, setRegName] = useState("");
  const [regPhone, setRegPhone] = useState("");
  const [regEmail, setRegEmail] = useState("");
  const [regCategory, setRegCategory] = useState("");
  const [regPurpose, setRegPurpose] = useState("");
  const [registerBusy, setRegisterBusy] = useState(false);
  const [registerError, setRegisterError] = useState<string | null>(null);
  const [registered, setRegistered] = useState<string | null>(null);

  async function search() {
    if (!client || query.trim() === "") return;
    setSearching(true);
    setSearchError(null);
    setFound(null);
    try {
      setFound(await client.visitors.lookup(query.trim()));
    } catch (cause) {
      setSearchError(cause instanceof ApiRequestError && cause.code === "not_found" ? t("reception.visitor.notFound") : describeError(t, cause));
    } finally {
      setSearching(false);
    }
  }

  function use(match: VisitorMatch) {
    onSelect({ id: match.id, name: match.name });
    setFound(null);
    setQuery("");
  }

  function openRegister() {
    setRegistering(true);
    setRegisterError(null);
    setRegistered(null);
  }

  async function register() {
    if (!client) return;
    const name = regName.trim();
    const phone = regPhone.trim();
    setRegisterBusy(true);
    setRegisterError(null);
    try {
      const result = await client.visitors.register({
        name,
        phone,
        ...(regEmail.trim() === "" ? {} : { email: regEmail.trim() }),
        ...(regCategory.trim() === "" ? {} : { category: regCategory.trim() }),
        ...(regPurpose.trim() === "" ? {} : { purpose: regPurpose.trim() }),
      });
      onSelect({ id: result.id, name: result.name });
      setRegistering(false);
      setRegistered(t("reception.visitor.registered", { name: result.name, pass: result.pass_reference }));
      setRegName("");
      setRegPhone("");
      setRegEmail("");
      setRegCategory("");
      setRegPurpose("");
    } catch (cause) {
      setRegisterError(describeError(t, cause));
    } finally {
      setRegisterBusy(false);
    }
  }

  return (
    <fieldset className="qms-stack">
      <legend className="qms-heading">{t("reception.visitor.title")}</legend>
      {visitor && (
        <p className="qms-row">
          <span>{t(visitor.name === null ? "reception.visitor.selectedNoName" : "reception.visitor.selected", { name: visitor.name ?? "" })}</span>
          <Button type="button" variant="secondary" onClick={onClear}>
            {t("reception.visitor.clear")}
          </Button>
        </p>
      )}
      {registered !== null && (
        <p role="status" className="qms-muted">
          {registered}
        </p>
      )}
      {!visitor && (
        <>
          <div className="qms-row">
            <TextField id="reception-visitor-query" label={t("reception.visitor.searchLabel")} value={query} onChange={(event) => setQuery(event.target.value)} />
            <Button type="button" variant="secondary" disabled={searching || query.trim() === ""} onClick={() => void search()}>
              {t(searching ? "reception.visitor.searching" : "reception.visitor.search")}
            </Button>
          </div>
          {searchError !== null && <ErrorAlert>{searchError}</ErrorAlert>}
          {found && (
            <p className="qms-row">
              <span>
                {found.name ?? found.external_code}
                {found.category && <span className="qms-muted"> · {found.category}</span>}
                {found.phone && <span className="qms-muted"> · {found.phone}</span>}
              </span>
              <Button type="button" onClick={() => use(found)}>
                {t("reception.visitor.use")}
              </Button>
            </p>
          )}
          {!registering && (
            <Button type="button" variant="secondary" onClick={openRegister}>
              {t("reception.visitor.registerToggle")}
            </Button>
          )}
          {registering && (
            <fieldset className="qms-stack" aria-label={t("reception.visitor.registerTitle")}>
              <legend>{t("reception.visitor.registerTitle")}</legend>
              <TextField id="reception-visitor-name" label={t("reception.visitor.name")} value={regName} onChange={(event) => setRegName(event.target.value)} />
              <TextField id="reception-visitor-phone" label={t("reception.visitor.phone")} value={regPhone} onChange={(event) => setRegPhone(event.target.value)} />
              <TextField id="reception-visitor-email" label={t("reception.visitor.email")} value={regEmail} onChange={(event) => setRegEmail(event.target.value)} />
              <TextField id="reception-visitor-category" label={t("reception.visitor.category")} value={regCategory} onChange={(event) => setRegCategory(event.target.value)} />
              <TextField id="reception-visitor-purpose" label={t("reception.visitor.purpose")} value={regPurpose} onChange={(event) => setRegPurpose(event.target.value)} />
              {registerError !== null && <ErrorAlert>{registerError}</ErrorAlert>}
              <div className="qms-row">
                <Button type="button" disabled={registerBusy || regName.trim() === "" || regPhone.trim() === ""} onClick={() => void register()}>
                  {t(registerBusy ? "reception.visitor.registering" : "reception.visitor.register")}
                </Button>
                <Button type="button" variant="secondary" onClick={() => setRegistering(false)}>
                  {t("reception.visitor.registerCancel")}
                </Button>
              </div>
            </fieldset>
          )}
        </>
      )}
    </fieldset>
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

/** Refusals of `POST /journeys` this screen has a sentence for (ticket 31). */
const JOURNEY_REFUSALS = new Set(["journeys_disabled", "journey_template_inactive", "journey_cross_site", "journey_template_empty"]);

/**
 * Issue a multi-stop Journey in one action (FR-ISS-022): from a template, whose own order and stops are used
 * (FR-QUE-060), or ad hoc, picking the stops and whether they are ordered (FR-QUE-061) or unordered (FR-QUE-062). Off
 * by a checkbox so a single walk-in ticket, the common case, keeps its own short form above untouched.
 */
function JourneySection({
  siteId,
  services,
  classes,
  nameOf,
}: {
  siteId: string;
  services: SiteServiceItem[];
  classes: PriorityClass[];
  nameOf: (names: Record<string, string>) => string;
}) {
  const { t } = useI18n();
  const { client } = useApi();
  const [open, setOpen] = useState(false);
  const [templates, setTemplates] = useState<JourneyTemplateSummary[] | null>(null);
  const [templatesError, setTemplatesError] = useState<unknown>(null);
  const [templateId, setTemplateId] = useState("");
  /** Ad hoc stops, in the order they were picked (FR-QUE-060). */
  const [picked, setPicked] = useState<string[]>([]);
  const [ordered, setOrdered] = useState(true);
  const [priority, setPriority] = useState("");
  const [visitor, setVisitor] = useState<{ id: string; name: string | null } | null>(null);
  const [note, setNote] = useState("");
  const [issuing, setIssuing] = useState(false);
  const [issueError, setIssueError] = useState<string | null>(null);
  const [result, setResult] = useState<JourneyResult | null>(null);
  const pending = useRef<{ request: string; key: string } | null>(null);

  useEffect(() => {
    if (!open || !client || templates !== null) return;
    client.journeys.templatesForSite(siteId).then(setTemplates, (cause: unknown) => setTemplatesError(cause));
  }, [open, client, siteId, templates]);

  const template = templates?.find((tpl) => tpl.id === templateId) ?? null;
  const adHoc = templateId === "";

  function togglePicked(serviceId: string) {
    setPicked((current) => (current.includes(serviceId) ? current.filter((id) => id !== serviceId) : [...current, serviceId]));
  }

  function refusalText(cause: unknown): string {
    const reason = cause instanceof ApiRequestError ? cause.body?.details?.reason : undefined;
    if (cause instanceof ApiRequestError && cause.code === "conflict" && typeof reason === "string" && JOURNEY_REFUSALS.has(reason)) {
      return t(`reception.journey.refused.${reason}`);
    }
    return describeError(t, cause);
  }

  async function issue() {
    if (!client) return;
    if (adHoc && picked.length < 2) {
      setIssueError(t("reception.journey.error.min"));
      return;
    }
    const trimmedNote = note.trim();
    const request = adHoc
      ? `adhoc|${picked.join(",")}|${ordered}|${priority}|${visitor?.id ?? ""}|${trimmedNote}`
      : `template:${templateId}|${priority}|${visitor?.id ?? ""}|${trimmedNote}`;
    if (pending.current?.request !== request) pending.current = { request, key: newIdempotencyKey() };
    setIssuing(true);
    setIssueError(null);
    try {
      const journey = await client.journeys.issue(
        {
          ...(adHoc ? { service_ids: picked, ordered } : { journey_template_id: templateId }),
          ...(priority === "" ? {} : { priority_class_id: priority }),
          ...(visitor === null ? {} : { visitor_id: visitor.id }),
          ...(trimmedNote === "" ? {} : { purpose_note: trimmedNote }),
        },
        pending.current.key,
      );
      pending.current = null;
      setResult(journey);
      setVisitor(null);
      setNote("");
      setPicked([]);
    } catch (cause) {
      const unknown = cause instanceof ApiRequestError && (cause.code === "network_error" || cause.status >= 500);
      if (!unknown) pending.current = null;
      setIssueError(refusalText(cause));
    } finally {
      setIssuing(false);
    }
  }

  return (
    <Card>
      <label className="qms-row">
        <span>
          <input type="checkbox" checked={open} onChange={(event) => setOpen(event.target.checked)} /> {t("reception.journey.toggle")}
        </span>
      </label>
      {open && (
        <div className="qms-stack">
          {templatesError !== null && <ErrorAlert>{describeError(t, templatesError)}</ErrorAlert>}
          {templates && (
            <SelectField
              id="journey-template"
              label={t("reception.journey.template.label")}
              value={templateId}
              onChange={(event) => {
                setTemplateId(event.target.value);
                setPicked([]);
              }}
              options={[{ value: "", label: t("reception.journey.template.adHoc") }, ...templates.map((tpl) => ({ value: tpl.id, label: nameOf(tpl.name_i18n) }))]}
            />
          )}
          {!adHoc && template && (
            <div>
              <p className="qms-muted">{t(template.ordered ? "reception.journey.ordered.label" : "reception.journey.unordered.label")}</p>
              <ol className="qms-list">
                {template.stops.map((stop) => (
                  <li key={stop.seq}>{nameOf(stop.service_names)}</li>
                ))}
              </ol>
            </div>
          )}
          {adHoc && (
            <fieldset className="qms-stack">
              <legend className="qms-heading">{t("reception.journey.services.title")}</legend>
              {services.map((s) => {
                const index = picked.indexOf(s.id);
                return (
                  <div key={s.id} className="qms-row">
                    <label>
                      <input type="checkbox" checked={index !== -1} onChange={() => togglePicked(s.id)} /> {nameOf(s.name_i18n)}
                    </label>
                    {index !== -1 && <span className="qms-muted">{t("reception.journey.stopNumber", { seq: index + 1 })}</span>}
                  </div>
                );
              })}
              <div className="qms-row">
                <label>
                  <input type="radio" name="journey-ordered" checked={ordered} onChange={() => setOrdered(true)} /> {t("reception.journey.ordered.label")}
                </label>
                <label>
                  <input type="radio" name="journey-ordered" checked={!ordered} onChange={() => setOrdered(false)} /> {t("reception.journey.unordered.label")}
                </label>
              </div>
            </fieldset>
          )}
          {classes.length > 0 && (
            <SelectField
              id="journey-priority"
              label={t("reception.priority.label")}
              value={priority}
              onChange={(event) => setPriority(event.target.value)}
              options={[
                { value: "", label: nameOf(classes.find((c) => c.is_default)?.name_i18n ?? {}) },
                ...classes.filter((c) => !c.is_default && c.active).map((c) => ({ value: c.id, label: t("reception.priority.option", { name: nameOf(c.name_i18n), minutes: c.headstart_minutes }) })),
              ]}
            />
          )}
          <VisitorPanel visitor={visitor} onSelect={setVisitor} onClear={() => setVisitor(null)} />
          <TextField id="journey-note" label={t("reception.note.label")} value={note} maxLength={1000} onChange={(event) => setNote(event.target.value)} />
          {issueError !== null && <ErrorAlert>{issueError}</ErrorAlert>}
          <Button type="button" disabled={issuing || (!adHoc && templateId === "") || (adHoc && picked.length < 2)} onClick={() => void issue()}>
            {t(issuing ? "reception.journey.issuing" : "reception.journey.issue")}
          </Button>
        </div>
      )}

      {result && (
        <div data-testid="journey-result">
          <h3 className="qms-heading">{t("reception.journey.result.title")}</h3>
          <ol className="qms-list">
            {result.stops.map((stop) => (
              <li key={stop.seq} data-testid={`journey-result-stop-${stop.seq}`}>
                {stop.ticket
                  ? t("reception.journey.result.stop", { service: nameOf(stop.service_names), token: formatTokenNumber(stop.ticket.token_number) })
                  : t("reception.journey.result.planned", { service: nameOf(stop.service_names) })}
                {stop.soonest && <span className="qms-muted"> {t("reception.journey.result.soonest")}</span>}
              </li>
            ))}
          </ol>
        </div>
      )}
    </Card>
  );
}
