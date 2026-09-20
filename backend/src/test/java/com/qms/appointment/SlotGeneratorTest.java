package com.qms.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.qms.appointment.AppointmentRows.DateException;
import com.qms.appointment.AppointmentRows.Template;
import com.qms.appointment.BusinessWindow.Window;
import com.qms.appointment.SlotGenerator.Slot;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Slot templates, exceptions and the business-hours window combined into one date's slots (FR-APT-002..004). */
class SlotGeneratorTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);

    private static Template template(LocalTime start, LocalTime end, int slotMinutes, int capacity) {
        return template(start, end, slotMinutes, capacity, null, null);
    }

    private static Template template(LocalTime start, LocalTime end, int slotMinutes, int capacity, LocalDate validFrom, LocalDate validTo) {
        return new Template(UUID.randomUUID(), AppointmentLevel.SERVICE, UUID.randomUUID(), 1, start, end, slotMinutes, capacity, validFrom, validTo);
    }

    private static DateException exception(String type, LocalTime start, LocalTime end, Integer slotMinutes, Integer capacity) {
        return new DateException(UUID.randomUUID(), AppointmentLevel.SERVICE, UUID.randomUUID(), MONDAY, type, start, end, slotMinutes, capacity, Map.of());
    }

    private static final Optional<Window> ALL_DAY = Optional.of(new Window(LocalTime.MIN, LocalTime.MAX));

    @Test
    void slicesAFixedTemplateIntoEqualLengthSlotsWithItsCapacity() {
        List<Slot> slots = SlotGenerator.generate(List.of(template(LocalTime.of(9, 0), LocalTime.of(9, 30), 15, 2)), MONDAY, ALL_DAY, null);

        assertThat(slots).extracting(Slot::start, Slot::end, Slot::capacity)
                .containsExactly(tuple(LocalTime.of(9, 0), LocalTime.of(9, 15), 2), tuple(LocalTime.of(9, 15), LocalTime.of(9, 30), 2));
    }

    @Test
    void aPartialTrailingSlotThatDoesNotFitIsDropped() {
        List<Slot> slots = SlotGenerator.generate(List.of(template(LocalTime.of(9, 0), LocalTime.of(9, 40), 15, 1)), MONDAY, ALL_DAY, null);

        assertThat(slots).extracting(Slot::start, Slot::end).containsExactly(tuple(LocalTime.of(9, 0), LocalTime.of(9, 15)), tuple(LocalTime.of(9, 15), LocalTime.of(9, 30)));
    }

    @Test
    void theBusinessHoursWindowClipsTheTemplate() {
        Optional<Window> halfDay = Optional.of(new Window(LocalTime.of(9, 0), LocalTime.of(9, 20)));

        List<Slot> slots = SlotGenerator.generate(List.of(template(LocalTime.of(9, 0), LocalTime.of(10, 0), 15, 1)), MONDAY, halfDay, null);

        assertThat(slots).extracting(Slot::start, Slot::end).containsExactly(tuple(LocalTime.of(9, 0), LocalTime.of(9, 15)));
    }

    @Test
    void noWindowAtAllMeansTheDayIsFullySuppressed() {
        List<Slot> slots = SlotGenerator.generate(List.of(template(LocalTime.of(9, 0), LocalTime.of(10, 0), 15, 1)), MONDAY, Optional.empty(), null);

        assertThat(slots).isEmpty();
    }

    @Test
    void aTemplateOutsideItsValidityRangeContributesNothing() {
        Template expired = template(LocalTime.of(9, 0), LocalTime.of(10, 0), 15, 1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1));

        List<Slot> slots = SlotGenerator.generate(List.of(expired), MONDAY, ALL_DAY, null);

        assertThat(slots).isEmpty();
    }

    @Test
    void slotsFromDifferentTemplatesThatLandOnTheSameWindowSumTheirCapacity() {
        Template morning = template(LocalTime.of(9, 0), LocalTime.of(9, 15), 15, 2);
        Template extra = template(LocalTime.of(9, 0), LocalTime.of(9, 15), 15, 3);

        List<Slot> slots = SlotGenerator.generate(List.of(morning, extra), MONDAY, ALL_DAY, null);

        assertThat(slots).containsExactly(new Slot(LocalTime.of(9, 0), LocalTime.of(9, 15), 5));
    }

    @Test
    void aBlockedExceptionSuppressesEveryTemplateThatDayRegardlessOfHours() {
        List<Slot> slots = SlotGenerator.generate(List.of(template(LocalTime.of(9, 0), LocalTime.of(10, 0), 15, 1)), MONDAY, ALL_DAY, exception(DateException.BLOCKED, null, null, null, null));

        assertThat(slots).isEmpty();
    }

    @Test
    void anExtraExceptionOverridesSuppressionWithItsOwnWindowEvenWhenTheDayIsClosed() {
        DateException extra = exception(DateException.EXTRA, LocalTime.of(14, 0), LocalTime.of(14, 30), 15, 4);

        List<Slot> slots = SlotGenerator.generate(List.of(), MONDAY, Optional.empty(), extra);

        assertThat(slots).extracting(Slot::start, Slot::end, Slot::capacity)
                .containsExactly(tuple(LocalTime.of(14, 0), LocalTime.of(14, 15), 4), tuple(LocalTime.of(14, 15), LocalTime.of(14, 30), 4));
    }

    @Test
    void aReducedCapacityExceptionKeepsTheNormalWindowButOverridesCapacity() {
        DateException reduced = exception(DateException.REDUCED_CAPACITY, null, null, null, 1);

        List<Slot> slots = SlotGenerator.generate(List.of(template(LocalTime.of(9, 0), LocalTime.of(9, 15), 15, 5)), MONDAY, ALL_DAY, reduced);

        assertThat(slots).containsExactly(new Slot(LocalTime.of(9, 0), LocalTime.of(9, 15), 1));
    }
}
