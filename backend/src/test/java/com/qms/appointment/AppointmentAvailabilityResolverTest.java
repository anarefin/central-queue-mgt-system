package com.qms.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Most-specific-wins across the three levels (FR-APT-001): Agent beats Team beats Service. */
class AppointmentAvailabilityResolverTest {

    @Test
    void agentWinsWhenDefinedRegardlessOfTeamOrService() {
        List<String> resolved = AppointmentAvailabilityResolver.mostSpecific(List.of("agent"), List.of("team"), List.of("service"));

        assertThat(resolved).containsExactly("agent");
    }

    @Test
    void teamWinsWhenNoAgentRowsAreDefined() {
        List<String> resolved = AppointmentAvailabilityResolver.mostSpecific(List.of(), List.of("team"), List.of("service"));

        assertThat(resolved).containsExactly("team");
    }

    @Test
    void serviceIsTheFallbackWhenNeitherAgentNorTeamIsDefined() {
        List<String> resolved = AppointmentAvailabilityResolver.mostSpecific(List.of(), List.of(), List.of("service"));

        assertThat(resolved).containsExactly("service");
    }

    @Test
    void nothingDefinedAtAnyLevelResolvesToAnEmptyList() {
        List<String> resolved = AppointmentAvailabilityResolver.mostSpecific(List.of(), List.of(), List.of());

        assertThat(resolved).isEmpty();
    }

    @Test
    void theSamePrecedenceAppliesToASingleExceptionPerDate() {
        Optional<String> agentWins = AppointmentAvailabilityResolver.mostSpecific(Optional.of("agent"), Optional.of("team"), Optional.of("service"));
        Optional<String> teamWins = AppointmentAvailabilityResolver.mostSpecific(Optional.empty(), Optional.of("team"), Optional.of("service"));
        Optional<String> serviceWins = AppointmentAvailabilityResolver.mostSpecific(Optional.empty(), Optional.empty(), Optional.of("service"));
        Optional<String> none = AppointmentAvailabilityResolver.mostSpecific(Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(agentWins).contains("agent");
        assertThat(teamWins).contains("team");
        assertThat(serviceWins).contains("service");
        assertThat(none).isEmpty();
    }
}
