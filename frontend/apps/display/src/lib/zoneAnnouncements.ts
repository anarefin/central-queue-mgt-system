import type { DisplayState } from "@qms/api-client";
import type { AnnouncementQueue, ZoneAudioConfig } from "./announcementQueue";

/**
 * The zone's voice-announcement settings from its last loaded state, or a silent-audio-but-no-quiet-period default
 * before anything has loaded (the board renders nothing to announce yet at that point regardless).
 */
export function zoneAudioConfig(state: DisplayState | null): ZoneAudioConfig {
  const zone = state?.zone;
  return {
    chime: zone?.chime ?? "chime_standard",
    chimeVolume: zone?.chime_volume ?? 80,
    quietStart: zone?.quiet_start ?? null,
    quietEnd: zone?.quiet_end ?? null,
    announcementLanguages: zone?.announcement_languages ?? ["en"],
    maxAnnounceQueueDepth: zone?.max_announce_queue_depth ?? 5,
  };
}

/**
 * Builds and enqueues the announcement for a `ticket.called`/`ticket.reannounced` event (FR-DSP-020, FR-DSP-028).
 * The Counter's own last-loaded row supplies the fields the event itself does not carry (counter label, Service
 * names, its token prefix and spoken forms, its visitor-name flag); like `patchCounter`'s own fields, these settle
 * to the call's own Service within `REFRESH_INTERVAL_MS` of a Service change at that Counter (ticket 28's same
 * eventual-consistency window, see the ticket 29 traceability notes). Nothing is enqueued when the board has not
 * loaded that Counter yet, or the event carries no `announce_count` (never true for a real `ticket.called`/
 * `ticket.reannounced`, guarded here only so a malformed event cannot crash the board).
 */
export function enqueueAnnouncement(announcer: AnnouncementQueue, state: DisplayState | null, counterId: string, data: Record<string, unknown>): void {
  const row = state?.serving.find((entry) => entry.counter_id === counterId);
  const tokenNumber = (data.token_number as string | undefined) ?? row?.token_number;
  const announceCount = data.announce_count as number | undefined;
  if (!state || !row || !tokenNumber || announceCount === undefined) return;
  announcer.enqueue({
    ticketId: data.ticket_id as string,
    announceCount,
    counterId,
    tokenNumber,
    tokenPrefix: row.token_prefix,
    tokenPrefixSpoken: row.token_prefix_spoken,
    counterLabel: row.counter_label,
    serviceNames: row.service_names,
    floorLabel: state.zone.floor_label,
    announceVisitorName: row.announce_visitor_name,
  });
}
