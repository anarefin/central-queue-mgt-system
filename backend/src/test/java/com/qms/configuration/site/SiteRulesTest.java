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

    // ---- zone audio (ticket 29, FR-DSP-023, FR-DSP-025..027) --------------------------------------------------

    @Test
    void chimeIsOneOfTheShippedChimesAndDefaultsWhenNotGiven() {
        assertThat(SiteRules.chime(null)).isEqualTo("chime_standard");
        assertThat(SiteRules.chime("chime_alert")).isEqualTo("chime_alert");
        assertThat(failedField(() -> SiteRules.chime("bell.mp3"))).isEqualTo("chime:unknown_chime");
    }

    @Test
    void chimeVolumeIsZeroToOneHundredAndDefaultsToEighty() {
        assertThat(SiteRules.chimeVolume(null)).isEqualTo(80);
        assertThat(SiteRules.chimeVolume(0)).isZero();
        assertThat(SiteRules.chimeVolume(100)).isEqualTo(100);
        assertThat(failedField(() -> SiteRules.chimeVolume(101))).isEqualTo("chime_volume:Range");
        assertThat(failedField(() -> SiteRules.chimeVolume(-1))).isEqualTo("chime_volume:Range");
    }

    @Test
    void quietTimeParsesHhMmKeepsTheCurrentValueWhenNotGivenAndClearsOnAnEmptyString() {
        assertThat(SiteRules.quietTime("quiet_start", null, java.time.LocalTime.of(9, 0))).isEqualTo(java.time.LocalTime.of(9, 0));
        assertThat(SiteRules.quietTime("quiet_start", "22:00", null)).isEqualTo(java.time.LocalTime.of(22, 0));
        assertThat(SiteRules.quietTime("quiet_start", "", java.time.LocalTime.of(9, 0))).isNull();
        assertThat(failedField(() -> SiteRules.quietTime("quiet_start", "not-a-time", null))).isEqualTo("quiet_start:Pattern");
    }

    @Test
    void aQuietPeriodNeedsBothEndsOrNeither() {
        SiteRules.quietPeriodComplete(null, null); // no quiet period configured: fine
        SiteRules.quietPeriodComplete(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(6, 0)); // both ends: fine
        assertThat(failedField(() -> SiteRules.quietPeriodComplete(java.time.LocalTime.of(22, 0), null)))
                .isEqualTo("quiet_start:quiet_period_needs_both_ends");
    }

    @Test
    void announcementLanguagesAreOrderedDeduplicatedAndMustBeInstalledOrDefaultToEnglish() {
        assertThat(SiteRules.announcementLanguages(null, INSTALLED)).containsExactly("en");
        assertThat(SiteRules.announcementLanguages(List.of("bn", "en"), INSTALLED)).containsExactly("bn", "en");
        assertThat(failedField(() -> SiteRules.announcementLanguages(List.of("bn", "bn"), INSTALLED))).isEqualTo("announcement_languages:duplicate_language");
        assertThat(failedField(() -> SiteRules.announcementLanguages(List.of("fr"), INSTALLED))).isEqualTo("announcement_languages:unknown_language");
    }

    @Test
    void maxAnnounceQueueDepthIsOneToTwentyAndDefaultsToFive() {
        assertThat(SiteRules.maxAnnounceQueueDepth(null)).isEqualTo(5);
        assertThat(SiteRules.maxAnnounceQueueDepth(1)).isEqualTo(1);
        assertThat(SiteRules.maxAnnounceQueueDepth(20)).isEqualTo(20);
        assertThat(failedField(() -> SiteRules.maxAnnounceQueueDepth(0))).isEqualTo("max_announce_queue_depth:Range");
        assertThat(failedField(() -> SiteRules.maxAnnounceQueueDepth(21))).isEqualTo("max_announce_queue_depth:Range");
    }
}
