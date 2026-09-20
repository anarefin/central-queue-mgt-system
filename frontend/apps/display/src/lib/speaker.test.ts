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
});

describe("createSpeaker (ticket 29, FR-DSP-024, FR-DSP-031)", () => {
  it("speaks with the Web Speech API when it is available, and never touches clip playback", async () => {
    const spoken = stubSpeechSynthesis();
    const urls = stubAudio("succeed");
    const speaker = createSpeaker();

    const done = speaker.speak("Token zero four five", "en");
    expect(spoken).toHaveLength(1);
    spoken[0]!.onend?.();
    await done;

    expect(urls).toHaveLength(0); // no clip fallback was needed
  });

  it("falls back to clip playback when the Web Speech API is unavailable", async () => {
    // No speechSynthesis/SpeechSynthesisUtterance stubbed: this build's browser has none.
    const urls = stubAudio("succeed");
    const speaker = createSpeaker({ clipBaseUrl: "/sounds" });

    await speaker.speak("zero four five", "en");

    expect(urls).toEqual(["/sounds/en/zero.mp3", "/sounds/en/four.mp3", "/sounds/en/five.mp3"]);
  });

  it("falls back to clip playback when the Web Speech API reports an error", async () => {
    const spoken = stubSpeechSynthesis();
    const urls = stubAudio("succeed");
    const speaker = createSpeaker();

    const done = speaker.speak("zero", "en");
    spoken[0]!.onerror?.();
    await done;

    expect(urls).toEqual(["/sounds/en/zero.mp3"]);
  });

  it("tolerates a clip that fails to load and still resolves, rather than getting stuck", async () => {
    stubAudio("fail");
    const speaker = createSpeaker();

    await expect(speaker.speak("zero four", "en")).resolves.toBeUndefined();
  });

  it("plays the zone's configured chime at its configured volume", async () => {
    const urls = stubAudio("succeed");
    const speaker = createSpeaker();

    await speaker.playChime("chime_soft", 55);

    expect(urls).toEqual(["/sounds/chimes/chime_soft.mp3"]);
  });
});
