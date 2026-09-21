package com.qms.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure, tested without a database (the same "no DB needed" shape {@code ChangeCalculatorTest}/{@code
 * RosteredHoursTest} already set for a pure reporting helper). */
class NotificationCostIndicatorTest {

    @Test
    void internalChannelsAreFree() {
        assertThat(NotificationCostIndicator.forChannel("in_app")).isEqualTo("free");
        assertThat(NotificationCostIndicator.forChannel("staff_alert")).isEqualTo("free");
    }

    @Test
    void externalProviderChannelsAreLow() {
        assertThat(NotificationCostIndicator.forChannel("web_push")).isEqualTo("low");
        assertThat(NotificationCostIndicator.forChannel("email")).isEqualTo("low");
    }

    @Test
    void anUnrecognisedOrMissingChannelIsUnknownRatherThanSilentlyFree() {
        assertThat(NotificationCostIndicator.forChannel("sms")).isEqualTo("unknown");
        assertThat(NotificationCostIndicator.forChannel(null)).isEqualTo("unknown");
    }
}
