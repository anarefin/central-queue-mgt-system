package com.qms.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.security.Role;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeviceRulesTest {

    @Test
    void kindAcceptsOnlyKioskOrDisplay() {
        assertThat(DeviceRules.kind("kiosk")).isEqualTo(Role.KIOSK);
        assertThat(DeviceRules.kind("display")).isEqualTo(Role.DISPLAY);
        assertThatThrownBy(() -> DeviceRules.kind("agent")).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void aKioskHasNoZoneAndADisplayMustHaveOne() {
        assertThatThrownBy(() -> DeviceRules.zoneMatchesKind(Role.DISPLAY, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeviceRules.zoneMatchesKind(Role.KIOSK, UUID.randomUUID())).isInstanceOf(ApiException.class);
        DeviceRules.zoneMatchesKind(Role.KIOSK, null); // no exception
        DeviceRules.zoneMatchesKind(Role.DISPLAY, UUID.randomUUID()); // no exception
    }

    @Test
    void commandEventTypeMapsReloadAndConfigChanged() {
        assertThat(DeviceRules.commandEventType("reload")).isEqualTo("device.command");
        assertThat(DeviceRules.commandEventType("config_changed")).isEqualTo("config.changed");
        assertThatThrownBy(() -> DeviceRules.commandEventType("shutdown")).isInstanceOf(ApiException.class);
    }

    @Test
    void randomPairingCodeIsEightUnambiguousCharacters() {
        String code = DeviceRules.randomPairingCode();
        assertThat(code).hasSize(8).matches("[A-HJ-NP-Z2-9]+");
    }

    @Test
    void requiredRejectsBlankAndOverlong() {
        assertThatThrownBy(() -> DeviceRules.required("label", " ", 10)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeviceRules.required("label", "x".repeat(11), 10)).isInstanceOf(ApiException.class);
        assertThat(DeviceRules.required("label", " ok ", 10)).isEqualTo("ok");
    }

    // ---- display board configuration (ticket 28, FR-DSP-001..005, FR-DSP-007, FR-SEC-020) ---------------------------

    @Test
    void layoutDefaultsToNowServingTableAndRejectsAnythingElse() {
        assertThat(DeviceRules.layout(null)).isEqualTo("now_serving_table");
        assertThat(DeviceRules.layout("now_serving_table")).isEqualTo("now_serving_table");
        assertThatThrownBy(() -> DeviceRules.layout("split_media")).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void languageCycleDefaultsToTheSitesDefaultAndMustBeEnabledWithoutDuplicates() {
        assertThat(DeviceRules.languageCycle(null, "bn", List.of("bn", "en"))).containsExactly("bn");
        assertThat(DeviceRules.languageCycle(List.of(), "bn", List.of("bn", "en"))).containsExactly("bn");
        assertThat(DeviceRules.languageCycle(List.of("en", "bn"), "bn", List.of("bn", "en"))).containsExactly("en", "bn");
        assertThatThrownBy(() -> DeviceRules.languageCycle(List.of("fr"), "bn", List.of("bn", "en"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeviceRules.languageCycle(List.of("en", "en"), "bn", List.of("bn", "en"))).isInstanceOf(ApiException.class);
    }

    @Test
    void columnsDefaultToTokenAndCounterOnlyAndAlwaysIncludeBoth() {
        assertThat(DeviceRules.columns(null)).containsExactly("token", "counter");
        assertThat(DeviceRules.columns(List.of("token", "counter", "staff"))).containsExactly("token", "counter", "staff");
        assertThatThrownBy(() -> DeviceRules.columns(List.of("counter", "staff"))).as("must include token").isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeviceRules.columns(List.of("token"))).as("must include counter").isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeviceRules.columns(List.of("token", "counter", "wait_time"))).as("unknown column").isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeviceRules.columns(List.of("token", "counter", "token"))).as("duplicate").isInstanceOf(ApiException.class);
    }

    @Test
    void positiveIntFallsBackAndRejectsOutOfRange() {
        assertThat(DeviceRules.positiveInt("next_n", null, 4, 20)).isEqualTo(4);
        assertThat(DeviceRules.positiveInt("next_n", 7, 4, 20)).isEqualTo(7);
        assertThatThrownBy(() -> DeviceRules.positiveInt("next_n", 0, 4, 20)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeviceRules.positiveInt("next_n", 21, 4, 20)).isInstanceOf(ApiException.class);
    }

    @Test
    void assignmentIdsMustBeEmptyForZoneAndNonEmptyOtherwiseWithoutDuplicates() {
        assertThat(DeviceRules.assignmentScope(null)).isEqualTo("zone");
        assertThatThrownBy(() -> DeviceRules.assignmentScope("floor")).isInstanceOf(ApiException.class);

        assertThat(DeviceRules.assignmentIds("zone", null)).isEmpty();
        assertThatThrownBy(() -> DeviceRules.assignmentIds("zone", List.of(UUID.randomUUID()))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeviceRules.assignmentIds("counters", List.of())).isInstanceOf(ApiException.class);
        UUID counter = UUID.randomUUID();
        assertThat(DeviceRules.assignmentIds("counters", List.of(counter))).containsExactly(counter);
        assertThatThrownBy(() -> DeviceRules.assignmentIds("counters", List.of(counter, counter))).as("duplicate").isInstanceOf(ApiException.class);
    }
}
