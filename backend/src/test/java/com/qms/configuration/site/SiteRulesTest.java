package com.qms.configuration.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FR-CFG-002 / FR-I18N-002: the field rules for a site, without a database. */
class SiteRulesTest {

    private static final List<String> INSTALLED = List.of("en", "bn");

    private static String failedField(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            @SuppressWarnings("unchecked")
            List<Map<String, String>> fields = (List<Map<String, String>>) e.details().get("fields");
            return fields.getFirst().get("field") + ":" + fields.getFirst().get("code");
        }
        throw new AssertionError("expected validation_failed");
    }

    @Test
    void timezoneMustBeAnIanaZoneId() {
        assertThat(SiteRules.timezone(" Asia/Dhaka ")).isEqualTo("Asia/Dhaka");
        assertThat(failedField(() -> SiteRules.timezone("Mars/Olympus"))).isEqualTo("timezone:unknown_timezone");
        assertThat(failedField(() -> SiteRules.timezone("  "))).isEqualTo("timezone:NotBlank");
    }

    @Test
    void enabledLanguagesKeepTheirOrderAndMustIncludeTheDefault() {
        assertThat(SiteRules.languages("en", List.of("bn", "en"), INSTALLED)).containsExactly("bn", "en");
        assertThat(SiteRules.languages("bn", null, INSTALLED)).containsExactly("bn");
        assertThat(SiteRules.languages("bn", List.of(), INSTALLED)).containsExactly("bn");
        assertThat(failedField(() -> SiteRules.languages("en", List.of("bn"), INSTALLED))).isEqualTo("enabled_languages:must_include_default_language");
    }

    @Test
    void unknownAndRepeatedLanguagesAreRefused() {
        assertThat(failedField(() -> SiteRules.languages("fr", null, INSTALLED))).isEqualTo("default_language:unknown_language");
        assertThat(failedField(() -> SiteRules.languages("en", List.of("en", "fr"), INSTALLED))).isEqualTo("enabled_languages:unknown_language");
        assertThat(failedField(() -> SiteRules.languages("en", List.of("en", "en"), INSTALLED))).isEqualTo("enabled_languages:duplicate_language");
    }

    @Test
    void requiredTextIsTrimmedAndBoundedWhileOptionalTextMayBeCleared() {
        assertThat(SiteRules.required("name", "  Main campus ", 20)).isEqualTo("Main campus");
        assertThat(failedField(() -> SiteRules.required("name", "   ", 10))).isEqualTo("name:NotBlank");
        assertThat(failedField(() -> SiteRules.required("name", "x".repeat(11), 10))).isEqualTo("name:Size");
        assertThat(SiteRules.optional("building_label", "  ", 10)).isNull();
        assertThat(SiteRules.optional("building_label", " Block B ", 10)).isEqualTo("Block B");
        assertThatThrownBy(() -> SiteRules.optional("building_label", "x".repeat(11), 10)).isInstanceOf(ApiException.class);
    }

    @Test
    void codeAndDisplayOrderAreBounded() {
        assertThat(SiteRules.code(" MAIN_1-a ")).isEqualTo("MAIN_1-a");
        assertThat(failedField(() -> SiteRules.code("has space"))).isEqualTo("code:Pattern");
        assertThat(failedField(() -> SiteRules.code(null))).isEqualTo("code:NotBlank");
        assertThat(SiteRules.displayOrder(null)).isZero();
        assertThat(SiteRules.displayOrder(7)).isEqualTo(7);
        assertThat(failedField(() -> SiteRules.displayOrder(-1))).isEqualTo("display_order:Range");
    }
}
