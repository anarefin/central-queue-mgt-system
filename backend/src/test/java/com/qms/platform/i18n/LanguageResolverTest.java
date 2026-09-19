package com.qms.platform.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** FR-I18N-003: user/visitor preference → device setting → site default → system default, most specific wins. */
class LanguageResolverTest {

    private static LanguageResolver resolver(String siteDefault) {
        var props = new LanguageProperties("en", siteDefault, List.of("en", "bn"), null);
        return new LanguageResolver(props, () -> Optional.ofNullable(props.siteDefaultLanguage()));
    }

    @Test
    void userPreferenceBeatsEverything() {
        assertThat(resolver("en").resolve("bn", "en")).isEqualTo("bn");
    }

    @Test
    void deviceSettingBeatsSiteAndSystemDefaults() {
        assertThat(resolver("en").resolve(null, "bn")).isEqualTo("bn");
    }

    @Test
    void siteDefaultBeatsSystemDefault() {
        assertThat(resolver("bn").resolve(null, null)).isEqualTo("bn");
    }

    @Test
    void systemDefaultIsLastResort() {
        assertThat(resolver(null).resolve(null, null)).isEqualTo("en");
    }

    @Test
    void unsupportedPreferenceIsSkippedNotReturned() {
        assertThat(resolver("bn").resolve("fr", "de")).isEqualTo("bn");
    }

    @Test
    void blankPreferenceIsTreatedAsAbsent() {
        assertThat(resolver("bn").resolve("  ", "")).isEqualTo("bn");
    }

    @Test
    void regionTagsMatchTheirLanguage() {
        assertThat(resolver("en").resolve("bn-BD", null)).isEqualTo("bn");
    }

    @Test
    void acceptLanguageHeaderPicksFirstEnabledByQuality() {
        assertThat(resolver("en").fromAcceptLanguage("fr;q=0.9, bn-BD;q=0.8, en;q=0.4"))
                .contains("bn");
    }

    @Test
    void acceptLanguageWithNoEnabledLanguageIsEmpty() {
        assertThat(resolver("en").fromAcceptLanguage("fr, de;q=0.5")).isEmpty();
    }

    @Test
    void malformedAcceptLanguageIsEmptyNotAnError() {
        assertThat(resolver("en").fromAcceptLanguage(";;;q=abc")).isEmpty();
        assertThat(resolver("en").fromAcceptLanguage(null)).isEmpty();
    }
}
