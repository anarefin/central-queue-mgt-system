package com.qms.configuration.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NoticeRulesTest {

    @Test
    void typeAcceptsOnlyTheShippedSet() {
        assertThat(NoticeRules.type("image")).isEqualTo("image");
        assertThat(NoticeRules.type("video")).isEqualTo("video");
        assertThat(NoticeRules.type("rich_text")).isEqualTo("rich_text");
        assertThatThrownBy(() -> NoticeRules.type("audio")).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> NoticeRules.type(null)).isInstanceOf(ApiException.class);
    }

    @Test
    void contentMustCoverTheDefaultLanguageAndKeepsOneEntryPerEnabledLanguage() {
        // FR-I18N-032: an image containing text ships one asset per language.
        Map<String, String> kept = NoticeRules.content(Map.of("bn", "https://x/bn.png", "en", "https://x/en.png"), "bn", List.of("bn", "en"));
        assertThat(kept).containsEntry("bn", "https://x/bn.png").containsEntry("en", "https://x/en.png");

        assertThatThrownBy(() -> NoticeRules.content(null, "bn", List.of("bn", "en"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> NoticeRules.content(Map.of("fr", "x"), "bn", List.of("bn", "en")))
                .as("unknown language").isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> NoticeRules.content(Map.of("en", "x"), "bn", List.of("bn", "en")))
                .as("the site default language is required").isInstanceOf(ApiException.class);

        Map<String, String> blankDropped = NoticeRules.content(Map.of("bn", "x", "en", "  "), "bn", List.of("bn", "en"));
        assertThat(blankDropped).containsOnlyKeys("bn");
    }

    @Test
    void datesRequireBothEndsAndEndsAfterStarts() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        NoticeRules.dates(now, now.plusSeconds(1)); // no exception
        assertThatThrownBy(() -> NoticeRules.dates(null, now)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> NoticeRules.dates(now, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> NoticeRules.dates(now, now)).as("must be strictly after").isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> NoticeRules.dates(now, now.minusSeconds(1))).isInstanceOf(ApiException.class);
    }

    @Test
    void sortOrderDefaultsToZeroAndRejectsOutOfRange() {
        assertThat(NoticeRules.sortOrder(null)).isZero();
        assertThat(NoticeRules.sortOrder(5)).isEqualTo(5);
        assertThatThrownBy(() -> NoticeRules.sortOrder(-1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> NoticeRules.sortOrder(1_001)).isInstanceOf(ApiException.class);
    }
}
