package com.qms.configuration.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Field rules for the service catalogue (FR-CFG-010, FR-CFG-012..014, FR-I18N-010). */
class CatalogueRulesTest {

    private static final List<String> ENABLED = List.of("bn", "en");

    private static void assertInvalid(Runnable call, String field, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details().toString()).contains(field).contains(code));
    }

    @Test
    void namesKeepTheSiteLanguageOrderDropBlanksAndNeedOnlyTheDefaultLanguage() {
        Map<String, String> given = new LinkedHashMap<>();
        given.put("en", " Outpatient ");
        given.put("bn", "বহির্বিভাগ");

        assertThat(CatalogueRules.names("name_i18n", given, "bn", ENABLED)).containsExactly(Map.entry("bn", "বহির্বিভাগ"), Map.entry("en", "Outpatient"));
        assertThat(CatalogueRules.names("name_i18n", Map.of("bn", "বহির্বিভাগ", "en", "  "), "bn", ENABLED)).containsOnlyKeys("bn");
    }

    @Test
    void aMissingTranslationIsAWarningButAMissingDefaultLanguageNameIsAnError() {
        assertThat(CatalogueRules.missing(Map.of("bn", "x"), ENABLED)).containsExactly("en");
        assertThat(CatalogueRules.missing(Map.of("bn", "x", "en", "y"), ENABLED)).isEmpty();

        assertInvalid(() -> CatalogueRules.names("name_i18n", Map.of("en", "Outpatient"), "bn", ENABLED), "name_i18n", "default_language_required");
        assertInvalid(() -> CatalogueRules.names("name_i18n", Map.of("bn", "x", "fr", "y"), "bn", ENABLED), "name_i18n", "unknown_language");
        assertInvalid(() -> CatalogueRules.names("name_i18n", Map.of(), "bn", ENABLED), "name_i18n", "NotBlank");
        assertInvalid(() -> CatalogueRules.names("name_i18n", null, "bn", ENABLED), "name_i18n", "NotBlank");
        assertInvalid(() -> CatalogueRules.names("name_i18n", Map.of("bn", "x".repeat(201)), "bn", ENABLED), "name_i18n", "Size");
    }

    @Test
    void channelsAreKnownAndUniqueAndNoneGivenMeansEveryChannel() {
        assertThat(CatalogueRules.channels(null)).containsExactlyElementsOf(CatalogueRules.CHANNELS);
        assertThat(CatalogueRules.channels(List.of("reception", "kiosk"))).containsExactly("reception", "kiosk");
        assertThat(CatalogueRules.channels(List.of())).isEmpty();
        assertInvalid(() -> CatalogueRules.channels(List.of("fax")), "channels", "unknown_channel");
        assertInvalid(() -> CatalogueRules.channels(List.of("kiosk", "kiosk")), "channels", "duplicate_channel");
    }

    @Test
    void prefixesCodesMinutesWeightsAndChoicesAreBounded() {
        assertThat(CatalogueRules.tokenPrefix(" OPD ")).isEqualTo("OPD");
        assertInvalid(() -> CatalogueRules.tokenPrefix("OPD-1"), "token_prefix", "Pattern");
        assertInvalid(() -> CatalogueRules.tokenPrefix("ABCDEFGHI"), "token_prefix", "Size");
        assertInvalid(() -> CatalogueRules.tokenPrefix(" "), "token_prefix", "NotBlank");

        assertThat(CatalogueRules.outcomeCode("docs_missing")).isEqualTo("docs_missing");
        assertInvalid(() -> CatalogueRules.outcomeCode("Docs Missing"), "code", "Pattern");

        assertThat(CatalogueRules.minutes("expected_minutes", 15)).isEqualTo(15);
        assertInvalid(() -> CatalogueRules.minutes("expected_minutes", 0), "expected_minutes", "Range");
        assertInvalid(() -> CatalogueRules.minutes("sla_wait_minutes", null), "sla_wait_minutes", "NotNull");

        assertThat(CatalogueRules.preferenceWeight(null)).isEqualTo(1);
        assertInvalid(() -> CatalogueRules.preferenceWeight(0), "preference_weight", "Range");

        assertThat(CatalogueRules.choice("booking_mode", "walk_in_only", CatalogueRules.BOOKING_MODE)).isEqualTo("walk_in_only");
        assertInvalid(() -> CatalogueRules.choice("visitor_identifier", "always", CatalogueRules.VISITOR_IDENTIFIER), "visitor_identifier", "Pattern");
    }
}
