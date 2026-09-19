package com.qms.configuration.breaks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Break type fields without a database: names in every installed language, an optional maximum (FR-AGT-020, FR-I18N-011). */
class BreakTypeRulesTest {

    private static final List<String> INSTALLED = List.of("en", "bn");

    @Test
    void namesKeepTheInstalledLanguagesInOrderAndDropBlanks() {
        Map<String, String> given = new LinkedHashMap<>();
        given.put("bn", "  দুপুরের খাবার ");
        given.put("en", "Lunch");

        assertThat(BreakTypeRules.names(given, "en", INSTALLED)).containsExactly(Map.entry("en", "Lunch"), Map.entry("bn", "দুপুরের খাবার"));
        assertThat(BreakTypeRules.names(Map.of("en", "Lunch", "bn", "  "), "en", INSTALLED)).containsOnlyKeys("en");
    }

    @Test
    void theDefaultLanguageMustHaveANameBecauseAMissingTranslationFallsBackToIt() {
        assertThatThrownBy(() -> BreakTypeRules.names(Map.of("bn", "দুপুর"), "en", INSTALLED)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> BreakTypeRules.names(null, "en", INSTALLED)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> BreakTypeRules.names(Map.of(), "en", INSTALLED)).isInstanceOf(ApiException.class);
    }

    @Test
    void aLanguageThatIsNotInstalledOrANameThatIsTooLongIsRefused() {
        assertThatThrownBy(() -> BreakTypeRules.names(Map.of("en", "Lunch", "xx", "?"), "en", INSTALLED)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> BreakTypeRules.names(Map.of("en", "x".repeat(101)), "en", INSTALLED)).isInstanceOf(ApiException.class);
        assertThat(BreakTypeRules.names(Map.of("en", "x".repeat(100)), "en", INSTALLED)).hasSize(1);
    }

    @Test
    void theMaximumIsOptionalAndBetweenOneMinuteAndADay() {
        assertThat(BreakTypeRules.maxMinutes(null)).isNull();
        assertThat(BreakTypeRules.maxMinutes(1)).isEqualTo(1);
        assertThat(BreakTypeRules.maxMinutes(1440)).isEqualTo(1440);
        for (int bad : new int[] {0, -5, 1441}) {
            assertThatThrownBy(() -> BreakTypeRules.maxMinutes(bad)).isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }
}
