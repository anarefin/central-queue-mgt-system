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

/**
 * Speaks `text` with the Web Speech API, resolving `true` once it finishes, or `false` (never throwing) if TTS is
 * unavailable, fails, or the browser has voices loaded but none for `language` (ticket 65, FR-DSP-031's fallback
 * trigger). A browser that has not loaded any voice list yet (`getVoices()` returns `[]`) is treated as "try
 * anyway" rather than "no voice for this language", since an empty list here usually just means the async
 * `voiceschanged` load has not fired yet, not a genuine absence.
 */
function speakWithTts(text: string, language: string, voices?: VoicePreferences): Promise<boolean> {
  return new Promise((resolve) => {
    if (typeof window === "undefined" || typeof window.speechSynthesis === "undefined" || typeof SpeechSynthesisUtterance === "undefined") {
      resolve(false);
      return;
    }
    try {
      const available = window.speechSynthesis.getVoices();
      const voice = pickVoice(available, language, voices?.[language]);
      if (available.length > 0 && !voice) {
        resolve(false);
        return;
      }
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = SPEECH_LANGUAGE_TAGS[language] ?? language;
      if (voice) utterance.voice = voice;
      utterance.onend = () => resolve(true);
      utterance.onerror = () => resolve(false);
      window.speechSynthesis.speak(utterance);
    } catch {
      resolve(false);
    }
  });
}

/** Plays one audio clip URL to completion; never rejects, so a missing/broken asset does not stop the sequence. */
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
 * Builds a display's {@link Speaker} (ticket 29, reworked in ticket 65): text-to-speech per language, falling back
 * to a single shared chime plus the board's own visual highlight (FR-DSP-007) when TTS is unavailable, fails, or has
 * no voice for the language (FR-DSP-031). The original per-word digit-clip fallback (`/sounds/{language}/{word}.mp3`)
 * is gone: those clips were never shipped with this repository, so every real fallback silently requested a missing
 * asset. `apps/display/public/sounds/chime.wav` (ticket 65) is the one asset this fallback now plays, once per
 * announcement, logging a single console warning per {@link createSpeaker} call (effectively once per display
 * session) rather than once per announcement.
 */
export function createSpeaker(options: { clipBaseUrl?: string; voices?: VoicePreferences } = {}): Speaker {
  // Under the app's own base path (the proxy serves this app at /display/), not the site root.
  const soundsBaseUrl = options.clipBaseUrl ?? `${process.env.NEXT_PUBLIC_BASE_PATH ?? ""}/sounds`;
  let warnedThisSession = false;
  return {
    async speak(text, language) {
      const spoke = await speakWithTts(text, language, options.voices);
      if (spoke) return;
      if (!warnedThisSession) {
        warnedThisSession = true;
        // eslint-disable-next-line no-console -- ops-facing diagnostic, not a visitor-facing message (no i18n needed)
        console.warn("Speech synthesis is unavailable or has no voice for this language; using the chime fallback instead of a spoken announcement.");
      }
      await playClip(`${soundsBaseUrl}/chime.wav`);
    },
    playChime(chime, volumePercent) {
      return new Promise((resolve) => {
        if (typeof Audio === "undefined") {
          resolve();
          return;
        }
        try {
          const audio = new Audio(`${soundsBaseUrl}/chimes/${chime}.wav`);
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
