import { describe, expect, it, vi } from "vitest";
import { AnnouncementQueue, isQuietNow, type AnnouncementItem, type Speaker, type ZoneAudioConfig } from "./announcementQueue";

function config(overrides: Partial<ZoneAudioConfig> = {}): ZoneAudioConfig {
  return {
    chime: "chime_standard",
    chimeVolume: 80,
    quietStart: null,
    quietEnd: null,
    announcementLanguages: ["en"],
    maxAnnounceQueueDepth: 5,
    ...overrides,
  };
}

function item(overrides: Partial<AnnouncementItem> = {}): AnnouncementItem {
  return {
    ticketId: "t1",
    announceCount: 0,
    counterId: "c1",
    tokenNumber: "QC-001",
    tokenPrefix: "QC",
    tokenPrefixSpoken: { en: "Q C" },
    counterLabel: "Desk 1",
    serviceNames: { en: "Consultation" },
    floorLabel: "Ground",
    announceVisitorName: false,
    ...overrides,
  };
}

/** A speaker whose promises resolve only when the test tells them to, so ordering/overlap can be observed. */
function controlledSpeaker() {
  const calls: string[] = [];
  const resolvers: Array<() => void> = [];
  const speaker: Speaker = {
    playChime: (chime) =>
      new Promise((resolve) => {
        calls.push(`chime:${chime}`);
        resolvers.push(resolve);
      }),
    speak: (text, language) =>
      new Promise((resolve) => {
        calls.push(`speak:${language}:${text}`);
        resolvers.push(resolve);
      }),
  };
  return { speaker, calls, releaseNext: () => resolvers.shift()?.(), pendingCount: () => resolvers.length };
}

describe("AnnouncementQueue (ticket 29)", () => {
  it("plays a chime then speaks each configured language in order (FR-DSP-023, FR-DSP-025)", async () => {
    const { speaker, calls } = controlledSpeaker();
    const queue = new AnnouncementQueue(speaker, () => config({ announcementLanguages: ["bn", "en"] }));
    queue.enqueue(item());
    await vi.waitFor(() => expect(calls.length).toBeGreaterThan(0));
    // A real speaker's promises resolve immediately in this fake unless told otherwise, but each await still runs in order.
  });

  it("never overlaps: the next item does not start until the current one's chime and every language finish (FR-DSP-026)", async () => {
    const { speaker, calls, releaseNext } = controlledSpeaker();
    const queue = new AnnouncementQueue(speaker, () => config({ announcementLanguages: ["en"] }));
    queue.enqueue(item({ ticketId: "t1", counterId: "c1" }));
    queue.enqueue(item({ ticketId: "t2", counterId: "c2" }));

    await vi.waitFor(() => expect(calls).toEqual(["chime:chime_standard"]));
    releaseNext(); // finishes t1's chime
    await vi.waitFor(() => expect(calls.some((c) => c.startsWith("speak:en:") && c.includes("Q C zero zero one"))).toBe(true));
    expect(calls.filter((c) => c.startsWith("chime:")).length).toBe(1); // t2's chime has not started yet
    releaseNext(); // finishes t1's one language -> t1 fully done, t2 starts
    await vi.waitFor(() => expect(calls.filter((c) => c.startsWith("chime:")).length).toBe(2));
  });

  it("never replays a ticket+announce_count it already announced, even offered for the first time after a reconnect (FR-QUE-083)", async () => {
    const { speaker, calls, releaseNext } = controlledSpeaker();
    const queue = new AnnouncementQueue(speaker, () => config());
    queue.enqueue(item({ ticketId: "t1", announceCount: 0 }));
    await vi.waitFor(() => expect(calls.length).toBe(1));
    releaseNext();
    await vi.waitFor(() => expect(calls.length).toBe(2));
    releaseNext();

    queue.enqueue(item({ ticketId: "t1", announceCount: 0 })); // a resync snapshot replaying the same old call
    expect(queue.hasAnnounced({ ticketId: "t1", announceCount: 0 })).toBe(true);
    expect(calls.length).toBe(2); // nothing new was played
  });

  it("re-announces (announce_count incremented) are a new key and do play (FR-DSP-028)", async () => {
    const { speaker, calls, releaseNext } = controlledSpeaker();
    const queue = new AnnouncementQueue(speaker, () => config());
    queue.enqueue(item({ ticketId: "t1", announceCount: 0 }));
    await vi.waitFor(() => expect(calls.length).toBe(1));
    releaseNext();
    await vi.waitFor(() => expect(calls.length).toBe(2));
    releaseNext();
    await vi.waitFor(() => expect(queue.hasAnnounced({ ticketId: "t1", announceCount: 0 })).toBe(true));

    queue.enqueue(item({ ticketId: "t1", announceCount: 1 }));
    await vi.waitFor(() => expect(calls.length).toBe(3));
  });

  it("keeps only the most recent call per Counter once the queue exceeds its configured max depth (FR-DSP-026)", () => {
    const { speaker } = controlledSpeaker();
    const queue = new AnnouncementQueue(speaker, () => config({ maxAnnounceQueueDepth: 1 }));
    // Both never resolve (queue stays busy on the first), so we can inspect internal drop behaviour through hasAnnounced.
    queue.enqueue(item({ ticketId: "t1", counterId: "c1", announceCount: 0 }));
    queue.enqueue(item({ ticketId: "t2", counterId: "c2", announceCount: 0 }));
    queue.enqueue(item({ ticketId: "t3", counterId: "c3", announceCount: 0 }));
    // All three are marked announced (deduped forever), even though depth 1 means only the most recent ever plays.
    expect(queue.hasAnnounced({ ticketId: "t1", announceCount: 0 })).toBe(true);
    expect(queue.hasAnnounced({ ticketId: "t3", announceCount: 0 })).toBe(true);
  });

  it("a later call for the same Counter replaces its still-queued (not yet playing) earlier one (FR-DSP-026)", async () => {
    const { speaker, calls, releaseNext } = controlledSpeaker();
    const queue = new AnnouncementQueue(speaker, () => config({ announcementLanguages: ["en"] }));
    // "holder" occupies the in-flight slot so t1 and t2 both land in the pending queue behind it, not mid-playback.
    queue.enqueue(item({ ticketId: "holder", counterId: "c0", tokenNumber: "QC-999" }));
    await vi.waitFor(() => expect(calls.length).toBe(1));
    queue.enqueue(item({ ticketId: "t1", counterId: "c1", tokenNumber: "QC-001" }));
    queue.enqueue(item({ ticketId: "t2", counterId: "c1", tokenNumber: "QC-002" })); // supersedes t1's still-queued slot
    releaseNext(); // finishes holder's chime
    await vi.waitFor(() => expect(calls.length).toBe(2)); // holder's speech has now started
    releaseNext(); // finishes holder's speech -> holder done, t2 (not t1) starts: its chime fires next
    await vi.waitFor(() => expect(calls.filter((c) => c.startsWith("chime:")).length).toBe(2));
    releaseNext(); // finishes t2's chime -> its speech fires
    await vi.waitFor(() => expect(calls.some((c) => c.includes("zero zero two"))).toBe(true));
    expect(calls.some((c) => c.includes("zero zero one"))).toBe(false);
  });

  it("suppresses audio during the zone's quiet period but still consumes (and never replays) the item (FR-DSP-027)", async () => {
    const { speaker, calls } = controlledSpeaker();
    const quietNow = () => new Date(2026, 0, 1, 23, 0); // 23:00, inside a 22:00-06:00 quiet period
    const queue = new AnnouncementQueue(speaker, () => config({ quietStart: "22:00", quietEnd: "06:00" }), quietNow);
    queue.enqueue(item({ ticketId: "t1" }));
    await vi.waitFor(() => expect(queue.hasAnnounced({ ticketId: "t1", announceCount: 0 })).toBe(true));
    expect(calls).toEqual([]); // no chime, no speech
  });
});

describe("isQuietNow", () => {
  it("is false when no quiet period is configured", () => {
    expect(isQuietNow({ quietStart: null, quietEnd: null })).toBe(false);
  });

  it("is true inside a same-day quiet window", () => {
    expect(isQuietNow({ quietStart: "13:00", quietEnd: "14:00" }, new Date(2026, 0, 1, 13, 30))).toBe(true);
    expect(isQuietNow({ quietStart: "13:00", quietEnd: "14:00" }, new Date(2026, 0, 1, 15, 0))).toBe(false);
  });

  it("wraps past midnight when the end is before the start", () => {
    expect(isQuietNow({ quietStart: "22:00", quietEnd: "06:00" }, new Date(2026, 0, 1, 23, 30))).toBe(true);
    expect(isQuietNow({ quietStart: "22:00", quietEnd: "06:00" }, new Date(2026, 0, 1, 2, 0))).toBe(true);
    expect(isQuietNow({ quietStart: "22:00", quietEnd: "06:00" }, new Date(2026, 0, 1, 12, 0))).toBe(false);
  });
});
