"use client";

import { ApiRequestError, type AgentAvailability, type BreakType } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField, StatusBadge, TextField } from "@qms/ui";
import { useEffect, useState } from "react";
import { describeError, useList } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

/** Reasons the API gives a refused availability change that this screen has a sentence for. */
const REFUSALS = new Set(["ticket_in_progress", "no_live_session", "already_on_break", "not_on_break", "session_not_open"]);

function Row({ agent, types, onChanged }: { agent: AgentAvailability; types: BreakType[]; onChanged: () => void }) {
  const { t, language } = useI18n();
  const { client } = useApi();
  const [typeId, setTypeId] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const who = agent.agent_name ?? agent.agent_id;
  const onBreak = agent.status === "on_break";
  const nameOf = (names: Record<string, string>) => names[language] ?? names.en ?? Object.values(names)[0] ?? "";

  async function change(status: "on_break" | "available") {
    if (!client) return;
    setBusy(true);
    setError(null);
    try {
      await client.breaks.setAvailability(agent.agent_id, {
        status,
        ...(status === "on_break" ? { break_type_id: typeId } : {}),
        ...(reason.trim() === "" ? {} : { reason: reason.trim() }),
      });
      setReason("");
      setTypeId("");
      onChanged();
    } catch (cause) {
      const detail = cause instanceof ApiRequestError ? cause.body?.details?.reason : undefined;
      setError(cause instanceof ApiRequestError && cause.code === "conflict" && typeof detail === "string" && REFUSALS.has(detail) ? t(`availability.refused.${detail}`) : describeError(t, cause));
      // The server knows best: a refusal may mean the row on screen is stale.
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  return (
    <li>
      <div className="qms-stack qms-grow">
        <div className="qms-row">
          <strong>{who}</strong>
          <StatusBadge status={agent.status === "available" ? "up" : "not_configured"}>{t(`availability.status.${agent.status}`)}</StatusBadge>
        </div>
        <span className="qms-muted">{t("availability.row", { counter: agent.counter.label })}</span>
        {agent.break && <span className="qms-muted">{t("availability.onBreak", { type: nameOf(agent.break.type.name_i18n) })}</span>}
        {agent.status === "available" && (
          <SelectField
            id={`availability-type-${agent.agent_id}`}
            label={`${t("availability.breakType")} — ${who}`}
            value={typeId}
            onChange={(event) => setTypeId(event.target.value)}
            options={[{ value: "", label: t("availability.breakChoose") }, ...types.map((b) => ({ value: b.id, label: nameOf(b.name_i18n) }))]}
          />
        )}
        {(agent.status === "available" || onBreak) && (
          <TextField id={`availability-reason-${agent.agent_id}`} label={`${t("availability.reason")} — ${who}`} value={reason} maxLength={1000} onChange={(event) => setReason(event.target.value)} />
        )}
        <div className="qms-row">
          {agent.status === "available" && (
            <Button type="button" variant="secondary" disabled={busy || typeId === ""} aria-label={t("availability.putOnBreakFor", { agent: who })} onClick={() => void change("on_break")}>
              {t("availability.putOnBreak")}
            </Button>
          )}
          {onBreak && (
            <Button type="button" variant="secondary" disabled={busy} aria-label={t("availability.returnToServiceFor", { agent: who })} onClick={() => void change("available")}>
              {t("availability.returnToService")}
            </Button>
          )}
        </div>
        {error && <ErrorAlert>{error}</ErrorAlert>}
      </div>
    </li>
  );
}

/**
 * Agent availability (FR-AGT-024): the agents in the caller's scope who have a counter session open, and whether each is available or
 * on a break. A Team or Org Admin puts an agent on a break of a chosen type, or returns them to service, without waiting for the
 * agent. The rules are the agent's own F9: the ticket in progress must be resolved first. The API checks the permission and the
 * scope (FR-CFG-103, FR-CFG-106).
 */
export function AvailabilityAdmin() {
  const { t } = useI18n();
  const { client } = useApi();
  const agents = useList<AgentAvailability>(client ? () => client.breaks.availability() : null, [client]);
  const [types, setTypes] = useState<BreakType[]>([]);

  useEffect(() => {
    if (!client) return;
    let current = true;
    client.breaks
      .types()
      .then((loaded) => current && setTypes(loaded.items.filter((b) => b.active)))
      .catch(() => current && setTypes([]));
    return () => {
      current = false;
    };
  }, [client]);

  return (
    <div className="qms-stack">
      <p className="qms-muted">{t("availability.intro")}</p>
      <Card>
        <h2 className="qms-heading">{t("availability.title")}</h2>
        {agents.error !== null && <ErrorAlert>{describeError(t, agents.error)}</ErrorAlert>}
        {agents.items?.length === 0 && <p className="qms-muted">{t("availability.none")}</p>}
        {agents.items && agents.items.length > 0 && (
          <ul className="qms-list">
            {agents.items.map((agent) => (
              <Row key={agent.agent_id} agent={agent} types={types} onChanged={agents.reload} />
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
