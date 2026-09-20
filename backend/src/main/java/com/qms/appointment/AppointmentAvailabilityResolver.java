package com.qms.appointment;

import java.util.List;
import java.util.Optional;

/**
 * Most-specific-wins resolution across the three levels (FR-APT-001): Agent beats Team beats Service. Pure and
 * generic so it is directly testable and shared by slot templates (a list per weekday) and date exceptions (at most
 * one per date).
 */
final class AppointmentAvailabilityResolver {

    private AppointmentAvailabilityResolver() {}

    /** The most specific non-empty list of rows; every level empty means nothing is defined at all. */
    static <T> List<T> mostSpecific(List<T> agent, List<T> team, List<T> service) {
        if (agent != null && !agent.isEmpty()) return agent;
        if (team != null && !team.isEmpty()) return team;
        return service == null ? List.of() : service;
    }

    /** The most specific present exception for a date. */
    static <T> Optional<T> mostSpecific(Optional<T> agent, Optional<T> team, Optional<T> service) {
        if (agent != null && agent.isPresent()) return agent;
        if (team != null && team.isPresent()) return team;
        return service == null ? Optional.empty() : service;
    }
}
