package com.qms.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A counter session as the console shows it (SRS §11, §19.3): who sits where, which Services they serve, the ticket
 * in progress and the tickets the agent holds ({@code held}, at most {@code hold_limit}; FR-AGT-013), and the {@code break} they are on while the session is
 * {@code on_break} (FR-AGT-021). {@code ticket} is the first ticket in progress and {@code tickets} all of them: more than one only
 * when the Services allow parallel serving (FR-AGT-010, FR-AGT-011). {@code can_call} says whether a call would be taken now, and
 * {@code call_timeout_seconds} how long a called ticket waits for its Agent before they are prompted (FR-QUE-032). Everything the
 * console needs to restore itself after a refresh is here (FR-AGT-004): the console holds no state the server does not, and {@code ticket.version} is what it sends back as {@code If-Match} (§20.1).
 */
public record SessionResponse(
        UUID id,
        CounterRef counter,
        @JsonProperty("agent_id") UUID agentId,
        String state,
        @JsonProperty("opened_at") Instant openedAt,
        @JsonProperty("closed_at") Instant closedAt,
        List<ServiceRef> services,
        SessionTicket ticket,
        List<SessionTicket> tickets,
        List<SessionTicket> held,
        @JsonProperty("hold_limit") int holdLimit,
        @JsonProperty("break") Break onBreak,
        @JsonProperty("can_call") boolean canCall,
        @JsonProperty("call_timeout_seconds") int callTimeoutSeconds) {

    /** The break type a break is of, with the longest it may run (FR-AGT-020). */
    public record BreakType(UUID id, @JsonProperty("name_i18n") Map<String, String> nameI18n, @JsonProperty("max_minutes") Integer maxMinutes) {}

    /** The break a session is on, so the console can show it and its clock after a refresh (FR-AGT-021, FR-AGT-022). */
    public record Break(UUID id, BreakType type, @JsonProperty("started_at") Instant startedAt) {}

    public record CounterRef(UUID id, String label, @JsonProperty("zone_id") UUID zoneId, @JsonProperty("zone_name") String zoneName, @JsonProperty("site_id") UUID siteId) {}

    /** A Service a counter serves, with the weight of its link: 1 is primary, higher is a fallback (FR-CFG-011). */
    public record ServiceRef(UUID id, @JsonProperty("name_i18n") Map<String, String> nameI18n, @JsonProperty("preference_weight") int preferenceWeight) {}

    public record Named(UUID id, @JsonProperty("name_i18n") Map<String, String> nameI18n) {}

    /** What an agent may record when completing this ticket's Service (FR-AGT-032). */
    public record Outcome(UUID id, String code, @JsonProperty("label_i18n") Map<String, String> labelI18n) {}

    /**
     * A ticket bound to the session: the one in progress ({@code called} or {@code serving}) or one of those held.
     * {@code waitSeconds} is how long it waited in the queue before it was called. {@code announceCount} of {@code announceLimit} is how many times this call has been
     * re-announced (FR-DSP-028); {@code missCount} of {@code missLimit} how many times it has been missed, the Miss after the
     * limit closing it as a no-show (FR-QUE-050). {@code callTimedOut} is set once a called ticket has waited for its Agent longer than the
     * call timeout, which is when the Agent may return it to the queue (FR-QUE-032). {@code isAppointment} says the visitor came in with an
     * appointment (FR-AGT-030). {@code visitor} and {@code purposeNote} carry only the visitor fields the caller's role is configured to
     * see, and only those the ticket has: a field outside the set is left out of the response, not sent for the screen to hide (FR-AGT-034).
     */
    public record SessionTicket(
            UUID id,
            @JsonProperty("token_number") String tokenNumber,
            String state,
            int version,
            Named service,
            @JsonProperty("origin_channel") String originChannel,
            @JsonProperty("is_appointment") boolean isAppointment,
            @JsonInclude(JsonInclude.Include.NON_NULL) VisitorView visitor,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("purpose_note") String purposeNote,
            @JsonProperty("priority_class") Named priorityClass,
            @JsonProperty("queued_at") Instant queuedAt,
            @JsonProperty("called_at") Instant calledAt,
            @JsonProperty("served_at") Instant servedAt,
            @JsonProperty("wait_seconds") int waitSeconds,
            @JsonProperty("announce_count") int announceCount,
            @JsonProperty("announce_limit") int announceLimit,
            @JsonProperty("miss_count") int missCount,
            @JsonProperty("miss_limit") int missLimit,
            @JsonProperty("call_timed_out") boolean callTimedOut,
            List<Outcome> outcomes,
            @JsonProperty("journey_stops") List<JourneyStop> journeyStops) {}

    /**
     * One other stop of this ticket's Visit's Journey (FR-AGT-031): {@code ticket_id} and {@code token_number} are null
     * and {@code state} is {@code "planned"} while an ordered Journey has not reached it yet (FR-QUE-061). Empty for a
     * ticket that is not part of a Journey.
     */
    public record JourneyStop(
            int seq, Named service, String state, @JsonProperty("ticket_id") UUID ticketId, @JsonProperty("token_number") String tokenNumber) {}

    /** The visitor of a ticket as the console may see them (FR-AGT-030); a field the role may not see, or the ticket does not have, is absent. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VisitorView(String code, String name, String category) {}
}
