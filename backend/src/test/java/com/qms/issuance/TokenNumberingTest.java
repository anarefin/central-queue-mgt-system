package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** The default numbering rule of SRS §4.4 (FR-QUE-201): prefix, separator, padded sequence, daily reset key. */
class TokenNumberingTest {

    @Test
    void aTokenNumberIsPrefixSeparatorAndPaddedSequenceInWesternArabicDigits() {
        assertThat(TokenNumbering.format("S", 42)).isEqualTo("S-042");
        assertThat(TokenNumbering.format("QC", 1)).isEqualTo("QC-001");
        assertThat(TokenNumbering.format("D", 1000)).as("a longer sequence is never truncated").isEqualTo("D-1000");
        assertThat(TokenNumbering.format("স", 7)).as("digits stay Western Arabic whatever the prefix").isEqualTo("স-007");
    }

    @Test
    void theResetKeyIsTheSiteLocalCalendarDay() {
        Instant lateEveningUtc = Instant.parse("2026-09-19T20:30:00Z");

        assertThat(TokenNumbering.resetKey(lateEveningUtc, ZoneId.of("UTC"))).isEqualTo("2026-09-19");
        assertThat(TokenNumbering.resetKey(lateEveningUtc, ZoneId.of("Asia/Dhaka"))).as("already tomorrow in Dhaka (UTC+6)").isEqualTo("2026-09-20");
        assertThat(TokenNumbering.resetKey(lateEveningUtc, ZoneId.of("America/Los_Angeles"))).isEqualTo("2026-09-19");
    }
}
