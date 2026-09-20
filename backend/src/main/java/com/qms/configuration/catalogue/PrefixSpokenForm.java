package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * The spoken form of a token prefix in one language (ticket 29, FR-DSP-030): prefix {@code QC} might be spoken as
 * letters ("Q C") in English and as a configured Bangla phrase, so pronunciation is never guessed from the letters.
 */
public record PrefixSpokenForm(String prefix, String language, @JsonProperty("spoken_text") String spokenText, @JsonProperty("updated_at") Instant updatedAt) {}
