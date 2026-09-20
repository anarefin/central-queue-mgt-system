import { announcementText, type AnnouncementData } from "./announcementText";

/** One ticket's call or re-announce, waiting to be spoken (ticket 29). */
export interface AnnouncementItem extends AnnouncementData {
  ticketId: string;
  /** The ticket's `announce_count` at the moment of this call (FR-QUE-083's dedupe key, together with `ticketId`). */
  announceCount: number;
  counterId: string;
  announceVisitorName: boolean;
}

/** A zone's voice-announcement settings, read live off its `display-state`/`zone:` feed (ticket 29). */
export interface ZoneAudioConfig {
  chime: string;
  chimeVolume: number;
  /** "HH:mm", or null when this end of the quiet period is not set; both are set together or neither is (FR-DSP-027). */
  quietStart: string | null;
  quietEnd: string | null;
  /** The languages announcements play in, in order (FR-DSP-023). */
  announcementLanguages: string[];
  /** The most announcements ever pending at once; beyond it, only the most recent call per Counter survives (FR-DSP-026). */
  maxAnnounceQueueDepth: number;
}

/** Plays one chime, or speaks one utterance in one language, resolving once playback finishes (never rejects: a playback failure just moves on). */
export interface Speaker {
  playChime(chime: string, volumePercent: number): Promise<void>;
  speak(text: string, language: string): Promise<void>;
}

/** True when `at` (defaults to now) falls inside the zone's configured quiet period (FR-DSP-027), wrapping past midnight when `quietEnd` is before `quietStart`. */
export function isQuietNow(config: Pick<ZoneAudioConfig, "quietStart" | "quietEnd">, at: Date = new Date()): boolean {
  if (!config.quietStart || !config.quietEnd) return false;
  const minutes = at.getHours() * 60 + at.getMinutes();
  const start = toMinutes(config.quietStart);
  const end = toMinutes(config.quietEnd);
  return start === end ? true : start < end ? minutes >= start && minutes < end : minutes >= start || minutes < end;
}

function toMinutes(hhmm: string): number {
  const [hours, mins] = hhmm.split(":").map(Number);
  return (hours ?? 0) * 60 + (mins ?? 0);
}

/**
 * Queues call announcements for one zone's audio (ticket 29): plays them one at a time, never overlapping
 * (FR-DSP-026), skips a ticket+announce_count pair it has already played -- including the very first time it is
 * offered, so a snapshot replayed after a reconnect never re-announces an old call (FR-QUE-083) -- and, when the
 * queue would grow past `config().maxAnnounceQueueDepth`, drops the oldest pending items so only the most recent
 * call per Counter is ever waiting (FR-DSP-026). A quiet period silences a pending item's audio but never its
 * dequeue: the item is still consumed and marked as announced (FR-DSP-027).
 */
export class AnnouncementQueue {
  private pending: AnnouncementItem[] = [];
  private announced = new Set<string>();
  private draining = false;

  constructor(
    private readonly speaker: Speaker,
    private readonly config: () => ZoneAudioConfig,
    private readonly now: () => Date = () => new Date(),
  ) {}

  /** Whether `item` has already been played (or queued to play), for tests and callers that want to check without enqueuing. */
  hasAnnounced(item: Pick<AnnouncementItem, "ticketId" | "announceCount">): boolean {
    return this.announced.has(key(item));
  }

  enqueue(item: AnnouncementItem): void {
    if (this.announced.has(key(item))) return; // FR-QUE-083
    this.announced.add(key(item));
    this.pending = this.pending.filter((queued) => queued.counterId !== item.counterId); // only the most recent call per Counter (FR-DSP-026)
    this.pending.push(item);
    const maxDepth = Math.max(1, this.config().maxAnnounceQueueDepth);
    while (this.pending.length > maxDepth) this.pending.shift();
    void this.drain();
  }

  private async drain(): Promise<void> {
    if (this.draining) return;
    this.draining = true;
    try {
      while (this.pending.length > 0) {
        const item = this.pending.shift()!;
        await this.announce(item);
      }
    } finally {
      this.draining = false;
    }
  }

  private async announce(item: AnnouncementItem): Promise<void> {
    const config = this.config();
    if (isQuietNow(config, this.now())) return; // FR-DSP-027: display already updated elsewhere, audio stays silent
    await this.speaker.playChime(config.chime, config.chimeVolume); // FR-DSP-025
    const languages = config.announcementLanguages.length > 0 ? config.announcementLanguages : ["en"];
    for (const language of languages) {
      // FR-DSP-023: in sequence, never overlapping -- each await finishes before the next language starts.
      await this.speaker.speak(announcementText(item, language, item.announceVisitorName), language);
    }
  }
}

function key(item: Pick<AnnouncementItem, "ticketId" | "announceCount">): string {
  return `${item.ticketId}:${item.announceCount}`;
}
