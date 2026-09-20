package com.qms.configuration.catalogue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL for {@link PrefixSpokenForm} (ticket 29, FR-DSP-030, FR-I18N-040, FR-I18N-041). */
@Repository
class PrefixSpokenFormRepository {

    private final JdbcTemplate jdbc;

    PrefixSpokenFormRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<PrefixSpokenForm> forPrefix(String prefix) {
        return jdbc.query(
                "SELECT prefix, language, spoken_text, updated_at FROM token_prefix_spoken_form WHERE prefix = ? ORDER BY language",
                (rs, i) -> new PrefixSpokenForm(rs.getString("prefix"), rs.getString("language"), rs.getString("spoken_text"), instant(rs.getObject("updated_at", OffsetDateTime.class))),
                prefix);
    }

    /** The languages of {@code prefix} that already have a spoken form recorded. */
    Set<String> languagesCovered(String prefix) {
        return Set.copyOf(jdbc.queryForList("SELECT language FROM token_prefix_spoken_form WHERE prefix = ?", String.class, prefix));
    }

    void upsert(String prefix, String language, String spokenText, Instant now) {
        jdbc.update(
                "INSERT INTO token_prefix_spoken_form (prefix, language, spoken_text, updated_at) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (prefix, language) DO UPDATE SET spoken_text = EXCLUDED.spoken_text, updated_at = EXCLUDED.updated_at",
                prefix, language, spokenText, ts(now));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }
}
