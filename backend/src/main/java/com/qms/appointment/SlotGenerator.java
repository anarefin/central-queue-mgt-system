package com.qms.appointment;

import com.qms.appointment.AppointmentRows.DateException;
import com.qms.appointment.AppointmentRows.Template;
import com.qms.appointment.BusinessWindow.Window;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the templates that apply on a weekday, the day's business-hours window and an optional exception into the
 * slots available on one date (FR-APT-002..004). Pure: everything it needs is passed in, so it is testable without a
 * database. Capacity here is the slot's defined capacity, not yet reduced by anything already booked: this ticket
 * introduces availability, not booking (ticket 33), so "remaining" and "defined" capacity are the same thing.
 */
final class SlotGenerator {

    record Slot(LocalTime start, LocalTime end, int capacity) {}

    private record SlotKey(LocalTime start, LocalTime end) {}

    private SlotGenerator() {}

    static List<Slot> generate(List<Template> templates, LocalDate date, Optional<Window> window, DateException exception) {
        if (exception != null && DateException.BLOCKED.equals(exception.type())) return List.of();
        if (exception != null && DateException.EXTRA.equals(exception.type())) {
            return slice(exception.start(), exception.end(), exception.slotMinutes(), exception.capacity());
        }
        if (window.isEmpty()) return List.of();
        Window w = window.get();
        Integer reducedCapacity = exception != null && DateException.REDUCED_CAPACITY.equals(exception.type()) ? exception.capacity() : null;

        Map<SlotKey, Integer> byWindow = new LinkedHashMap<>();
        for (Template t : templates) {
            if (!t.coversDate(date)) continue;
            LocalTime start = max(t.start(), w.open());
            LocalTime end = min(t.end(), w.close());
            if (!start.isBefore(end)) continue;
            int capacity = reducedCapacity != null ? reducedCapacity : t.capacity();
            for (Slot slot : slice(start, end, t.slotMinutes(), capacity)) {
                byWindow.merge(new SlotKey(slot.start(), slot.end()), slot.capacity(), Integer::sum);
            }
        }
        List<Slot> out = new ArrayList<>();
        byWindow.forEach((key, capacity) -> out.add(new Slot(key.start(), key.end(), capacity)));
        out.sort(Comparator.comparing(Slot::start).thenComparing(Slot::end));
        return out;
    }

    /** Fixed-length slots from {@code start} up to (not past) {@code end}; a partial trailing slot is dropped. */
    private static List<Slot> slice(LocalTime start, LocalTime end, int slotMinutes, int capacity) {
        List<Slot> out = new ArrayList<>();
        LocalTime cursor = start;
        while (true) {
            LocalTime next = cursor.plusMinutes(slotMinutes);
            if (next.isBefore(cursor) || next.isAfter(end)) break; // wrapped past midnight, or past the window
            out.add(new Slot(cursor, next, capacity));
            cursor = next;
        }
        return out;
    }

    private static LocalTime max(LocalTime a, LocalTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalTime min(LocalTime a, LocalTime b) {
        return a.isBefore(b) ? a : b;
    }
}
