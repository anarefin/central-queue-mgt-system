package com.qms.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.security.Role;
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
}
