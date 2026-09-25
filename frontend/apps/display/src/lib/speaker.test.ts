import { existsSync } from "node:fs";
import path from "node:path";
import type { ZoneChime } from "@qms/api-client";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createSpeaker } from "./speaker";

/** A `SpeechSynthesisUtterance`/`speechSynthesis` stand-in the test drives by hand, standing in for the Web Speech API. */
class FakeUtterance {
  lang = "";
  voice: unknown = null;
  onend: (() => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(public text: string) {}
}

function stubSpeechSynthesis(voices: Array<{ name: string; lang: string }> = []) {
  const spoken: FakeUtterance[] = [];
  const synth = {
    getVoices: () => voices,
    speak: (utterance: FakeUtterance) => {
      spoken.push(utterance);
    },
  };
  vi.stubGlobal("SpeechSynthesisUtterance", FakeUtterance as unknown as typeof SpeechSynthesisUtterance);
  vi.stubGlobal("speechSynthesis", synth);
  Object.defineProperty(window, "speechSynthesis", { value: synth, configurable: true, writable: true });
  return spoken;
}

/** A stand-in `Audio` element the test can resolve or fail by hand, capturing every URL constructed. */
function stubAudio(behaviour: "succeed" | "fail") {
  const urls: string[] = [];
  class FakeAudio {
    volume = 1;
    onended: (() => void) | null = null;
    onerror: (() => void) | null = null;
    constructor(public url: string) {
      urls.push(url);
    }
    play() {
      queueMicrotask(() => (behaviour === "succeed" ? this.onended?.() : this.onerror?.()));
      return Promise.resolve();
    }
  }
  vi.stubGlobal("Audio", FakeAudio as unknown as typeof Audio);
  return urls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("createSpeaker (ticket 29/65, FR-DSP-031)", () => {
  it("speaks with the Web Speech API when it is available, and never falls back to the chime", async () => {
    const spoken = stubSpeechSynthesis();
    const urls = stubAudio("succeed");
    const speaker = createSpeaker();

    const done = speaker.speak("Token zero four five", "en");
    expect(spoken).toHaveLength(1);
    spoken[0]!.onend?.();
    await done;

    expect(urls).toHaveLength(0); // no fallback was needed
  });

  it("falls back to the chime, and warns once, when the Web Speech API is unavailable", async () => {
    // No speechSynthesis/SpeechSynthesisUtterance stubbed: this build's browser has none.
    const urls = stubAudio("succeed");
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const speaker = createSpeaker({ clipBaseUrl: "/sounds" });

    await speaker.speak("zero four five", "en");

    expect(urls).toEqual(["/sounds/chime.wav"]);
    expect(warn).toHaveBeenCalledOnce();
  });

  it("falls back to the chime when the Web Speech API reports an error", async () => {
    const spoken = stubSpeechSynthesis();
    const urls = stubAudio("succeed");
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const speaker = createSpeaker();

    const done = speaker.speak("zero", "en");
    spoken[0]!.onerror?.();
    await done;

    expect(urls).toEqual(["/sounds/chime.wav"]);
  });

  it("falls back to the chime when the browser has loaded voices but none for the requested language", async () => {
    const spoken = stubSpeechSynthesis([{ name: "French Voice", lang: "fr-FR" }]);
    const urls = stubAudio("succeed");
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const speaker = createSpeaker();

    await speaker.speak("zero", "en");

    expect(spoken).toHaveLength(0); // never even attempted with the wrong-language voice
    expect(urls).toEqual(["/sounds/chime.wav"]);
  });

  it("logs the fallback warning only once per speaker (session), even across several fallback calls", async () => {
    stubAudio("succeed");
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const speaker = createSpeaker();

    await speaker.speak("zero", "en");
    await speaker.speak("one", "en");
    await speaker.speak("two", "en");

    expect(warn).toHaveBeenCalledOnce();
  });

  it("tolerates the chime asset failing to load and still resolves, rather than getting stuck", async () => {
    stubAudio("fail");
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const speaker = createSpeaker();

    await expect(speaker.speak("zero four", "en")).resolves.toBeUndefined();
  });

  it("plays the zone's configured chime at its configured volume (unchanged: the call-alert chime, not the fallback one)", async () => {
    const urls = stubAudio("succeed");
    const speaker = createSpeaker();

    await speaker.playChime("chime_soft", 55);

    expect(urls).toEqual(["/sounds/chimes/chime_soft.wav"]);
  });

  it("loads its sounds from under the app's own base path, where the proxy serves them, not the site root", async () => {
    vi.stubEnv("NEXT_PUBLIC_BASE_PATH", "/display");
    const urls = stubAudio("succeed");
    const speaker = createSpeaker();

    await speaker.playChime("chime_standard", 80);

    expect(urls).toEqual(["/display/sounds/chimes/chime_standard.wav"]);
    vi.unstubAllEnvs();
  });

  it("ships a clip for every chime a zone can be configured with", () => {
    const chimes: ZoneChime[] = ["chime_standard", "chime_soft", "chime_alert"];
    for (const chime of chimes) expect(existsSync(path.resolve(__dirname, "../../public/sounds/chimes", `${chime}.wav`)), chime).toBe(true);
  });
});
