import type { Speaker } from "./announcementQueue";

/** BCP 47 tags for the browser's speech synthesiser (FR-DSP-031); anything not listed here is passed through as-is. */
const SPEECH_LANGUAGE_TAGS: Record<string, string> = { en: "en-US", bn: "bn-BD" };

/** A per-language voice name to prefer, when the browser offers a matching {@link SpeechSynthesisVoice}, e.g. `{ bn: "Google বাংলা" }` (FR-DSP-031). */
export type VoicePreferences = Record<string, string>;

function pickVoice(voices: SpeechSynthesisVoice[], language: string, preferred?: string): SpeechSynthesisVoice | undefined {
  const tag = SPEECH_LANGUAGE_TAGS[language] ?? language;
  const matching = voices.filter((voice) => voice.lang.toLowerCase().startsWith(tag.slice(0, 2).toLowerCase()));
  const byName = preferred ? matching.find((voice) => voice.name.includes(preferred)) : undefined;
  return byName ?? matching[0];
}

/** Speaks `text` with the Web Speech API, resolving `true` once it finishes, or `false` (never throwing) if TTS is unavailable or fails (FR-DSP-031's fallback trigger). */
function speakWithTts(text: string, language: string, voices?: VoicePreferences): Promise<boolean> {
  return new Promise((resolve) => {
    if (typeof window === "undefined" || typeof window.speechSynthesis === "undefined" || typeof SpeechSynthesisUtterance === "undefined") {
      resolve(false);
      return;
    }
    try {
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = SPEECH_LANGUAGE_TAGS[language] ?? language;
      const voice = pickVoice(window.speechSynthesis.getVoices(), language, voices?.[language]);
      if (voice) utterance.voice = voice;
      utterance.onend = () => resolve(true);
      utterance.onerror = () => resolve(false);
      window.speechSynthesis.speak(utterance);
    } catch {
      resolve(false);
    }
  });
}

/** Plays one audio clip URL to completion; never rejects, so one missing/broken clip does not stop the sequence (FR-DSP-024's offline clip assembly). */
function playClip(url: string): Promise<void> {
  return new Promise((resolve) => {
    if (typeof Audio === "undefined") {
      resolve();
      return;
    }
    try {
      const audio = new Audio(url);
      audio.onended = () => resolve();
      audio.onerror = () => resolve();
      void audio.play().catch(() => resolve());
    } catch {
      resolve();
    }
  });
}

/**
 * Builds a display's {@link Speaker} (ticket 29): text-to-speech per language, falling back to pre-recorded clip
 * assembly when TTS is unavailable or fails (FR-DSP-024, FR-DSP-031). `clipBaseUrl` locates a language's clip set,
 * e.g. `/sounds/{language}/{clipId}.mp3`; this build's fallback plays the digits of the spoken text it was given as
 * clip ids (FR-DSP-030's "digits, prefixes, counters" clips), one clip at a time, tolerating any clip that fails to
 * load -- silence rather than a stuck queue -- since no clip audio ships with this repository yet (traceability notes).
 */
export function createSpeaker(options: { clipBaseUrl?: string; voices?: VoicePreferences } = {}): Speaker {
  const clipBaseUrl = options.clipBaseUrl ?? "/sounds";
  return {
    async speak(text, language) {
      const spoke = await speakWithTts(text, language, options.voices);
      if (spoke) return;
      for (const word of text.split(/\s+/).filter(Boolean)) {
        await playClip(`${clipBaseUrl}/${language}/${encodeURIComponent(word)}.mp3`);
      }
    },
    playChime(chime, volumePercent) {
      return new Promise((resolve) => {
        if (typeof Audio === "undefined") {
          resolve();
          return;
        }
        try {
          const audio = new Audio(`/sounds/chimes/${chime}.mp3`);
          audio.volume = Math.min(1, Math.max(0, volumePercent / 100));
          audio.onended = () => resolve();
          audio.onerror = () => resolve();
          void audio.play().catch(() => resolve());
        } catch {
          resolve();
        }
      });
    },
  };
}
