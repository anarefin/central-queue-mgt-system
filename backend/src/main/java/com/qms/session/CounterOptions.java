package com.qms.session;

import java.util.List;

/** The counters the caller may occupy (FR-AGT-001) and the Services each would let them serve (FR-AGT-003). */
public record CounterOptions(List<Item> items) {

    /** {@code occupied} is true while another session holds the counter, so the console can say so before it is tried. */
    public record Item(SessionResponse.CounterRef counter, boolean occupied, List<SessionResponse.ServiceRef> services) {}
}
