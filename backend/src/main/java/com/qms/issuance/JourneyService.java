package com.qms.issuance;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.issuance.JourneyRepository.NextStop;
import com.qms.issuance.JourneyRepository.StopRow;
import com.qms.issuance.JourneyRepository.TemplateRow;
import com.qms.issuance.TicketRepository.PriorityClassRef;
import com.qms.issuance.TicketRepository.ServiceTarget;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.ScopeGuard;
import com.qms.queue.QueueReads;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Journeys and multi-stop Visits (ticket 31, ADR-0007). Reception issues a Journey's stops in one action, all sharing
 * one Visit (FR-ISS-022); an ordered Journey issues only its first stop now and the rest one at a time, as each is
 * completed (FR-QUE-061, called from {@link com.qms.session.SessionService}); an unordered Journey issues every stop's
 * ticket up front (FR-QUE-062). Every stop is issued through {@link IssuanceService#issueIntoVisit}, so a Journey's
 * ticket goes through every check and writes every event and audit entry a single ticket does; this class only decides
 * which Services, in which order, and when.
 */
@Service
@Profile(Profiles.SERVING)
public class JourneyService {

    private static final int MIN_STOPS = 2;

    private final JourneyRepository journeys;
    private final IssuanceService issuance;
    private final TicketRepository tickets;
    private final TicketViews views;
    private final QueueReads queues;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final Clock clock;

    JourneyService(
            JourneyRepository journeys,
            IssuanceService issuance,
            TicketRepository tickets,
            TicketViews views,
            QueueReads queues,
            AuditWriter audit,
            ScopeGuard scope,
            Clock clock) {
        this.journeys = journeys;
        this.issuance = issuance;
        this.tickets = tickets;
        this.views = views;
        this.queues = queues;
        this.audit = audit;
        this.scope = scope;
        this.clock = clock;
    }

    /** Whether journeys are switched on for this deployment (feature flag "per profile"; see {@link #settingsEnabled(boolean)}). */
    @Transactional(readOnly = true)
    public boolean settingsEnabled() {
        return journeys.enabled();
    }

    @Transactional
    public boolean settingsEnabled(boolean enabled) {
        journeys.setEnabled(enabled, clock.instant());
        audit.record(AuditEvent.of("journey_settings.changed", "journey_settings", null).withAfter(Map.of("enabled", enabled)));
        return enabled;
    }

    /** The Journey templates offered at a Site, for Reception's picker (FR-QUE-060). */
    @Transactional(readOnly = true)
    public List<JourneyRepository.TemplateSummary> templatesForSite(UUID siteId) {
        return journeys.activeTemplatesForSite(siteId);
    }

    /**
     * Issues a Journey (FR-ISS-022): resolves its stops from a template or ad hoc, opens one Visit for all of them, plans
     * every stop as a {@code journey_stop} row (FR-QUE-060), then issues the tickets FR-QUE-061 or FR-QUE-062 calls for.
     */
    @Transactional
    public JourneyResponse issue(IssueJourneyRequest request, UUID actorId) {
        if (!journeys.enabled()) throw conflict("journeys_disabled");
        if (request == null) throw invalid("journey_template_id", "required");

        List<UUID> serviceIds;
        boolean ordered;
        UUID templateId = request.journeyTemplateId();
        if (templateId != null) {
            TemplateRow template = journeys.template(templateId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            if (!template.active()) throw conflict("journey_template_inactive");
            serviceIds = journeys.templateStopServiceIds(templateId);
            ordered = template.ordered();
        } else {
            if (request.serviceIds() == null || request.serviceIds().size() < MIN_STOPS) throw invalid("service_ids", "min_two");
            if (request.ordered() == null) throw invalid("ordered", "required");
            serviceIds = request.serviceIds();
            ordered = request.ordered();
        }
        if (serviceIds.size() < MIN_STOPS) throw conflict("journey_template_empty");

        List<ServiceTarget> targets = new ArrayList<>();
        for (UUID serviceId : serviceIds) {
            targets.add(tickets.serviceTarget(serviceId).orElseThrow(() -> invalid("service_ids", "invalid")));
        }
        UUID siteId = targets.getFirst().siteId();
        // A Journey never crosses Sites, the same as a Transfer (ADR-0002).
        if (targets.stream().anyMatch(target -> !target.siteId().equals(siteId))) throw conflict("journey_cross_site");
        scope.requireSite(siteId);
        if (request.priorityClassId() != null) requireIssuableClass(request.priorityClassId());

        Instant now = clock.instant();
        UUID visitId = UUID.randomUUID();
        tickets.insertVisit(visitId, siteId, now);
        journeys.setVisitJourney(visitId, templateId, ordered);
        List<UUID> stopIds = journeys.insertStops(visitId, serviceIds);

        int toIssueNow = ordered ? 1 : serviceIds.size();
        List<TicketResponse> issued = new ArrayList<>();
        for (int i = 0; i < toIssueNow; i++) {
            var command = new IssueCommand(
                    serviceIds.get(i), Channels.RECEPTION, actorId, ActorType.STAFF, null, request.priorityClassId(), request.visitorId(), true, request.purposeNote(), null, null);
            TicketResponse response = issuance.issueIntoVisit(command, visitId);
            journeys.linkTicket(stopIds.get(i), response.id());
            issued.add(response);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("visit_id", visitId.toString());
        after.put("ordered", ordered);
        after.put("service_ids", serviceIds.stream().map(UUID::toString).toList());
        if (templateId != null) after.put("journey_template_id", templateId.toString());
        audit.record(AuditEvent.of("visit.journey_issued", "visit", visitId).withAfter(after));

        return response(visitId, ordered, issued);
    }

    /**
     * FR-QUE-061: after a Journey stop's ticket completes, the next stop of an ordered Journey is issued into the same
     * Visit, inheriting the Priority class the completed ticket carried. Does nothing when {@code ticketId} is not part
     * of a Journey, the Journey is unordered (every stop was already issued at FR-QUE-062), or every stop has been
     * issued already. Called from {@code SessionService.complete}, in the same transaction as the completion.
     */
    @Transactional
    public void continueAfterCompletion(UUID ticketId, UUID priorityClassId, UUID actorId) {
        journeys.stopContext(ticketId).ifPresent(context -> {
            if (!context.journeyOrdered()) return;
            journeys.nextUnissuedStop(context.visitId()).ifPresent(next -> issueNextStop(next, priorityClassId, context, actorId));
        });
    }

    private void issueNextStop(NextStop next, UUID priorityClassId, JourneyRepository.StopContext context, UUID actorId) {
        var command = new IssueCommand(next.serviceId(), Channels.RECEPTION, actorId, ActorType.STAFF, null, priorityClassId, context.visitorId(), true, context.purposeNote(), null, null);
        TicketResponse response = issuance.issueIntoVisit(command, next.visitId());
        journeys.linkTicket(next.id(), response.id());
    }

    private JourneyResponse response(UUID visitId, boolean ordered, List<TicketResponse> issued) {
        Map<UUID, TicketResponse> byTicketId = new LinkedHashMap<>();
        for (TicketResponse ticket : issued) byTicketId.put(ticket.id(), ticket);
        List<StopRow> stops = journeys.stopsOfVisit(visitId);
        UUID soonest = ordered ? null : soonestOf(issued);
        List<JourneyResponse.Stop> stopViews = stops.stream()
                .map(stop -> {
                    if (stop.ticketId() == null) return new JourneyResponse.Stop(stop.seq(), stop.serviceId(), stop.serviceNames(), "planned", null, false);
                    TicketResponse ticket = byTicketId.getOrDefault(stop.ticketId(), views.of(tickets.ticket(stop.ticketId()).orElseThrow()));
                    return new JourneyResponse.Stop(stop.seq(), stop.serviceId(), stop.serviceNames(), ticket.state(), ticket, stop.ticketId().equals(soonest));
                })
                .toList();
        return new JourneyResponse(visitId, ordered, stopViews);
    }

    /** Among the stops just issued, the one with the best (lowest) place in its own Service's queue (FR-QUE-062). */
    private UUID soonestOf(List<TicketResponse> issued) {
        UUID best = null;
        Integer bestPosition = null;
        for (TicketResponse ticket : issued) {
            Integer position = queues.positionOf(ticket.id());
            if (position == null) continue;
            if (bestPosition == null || position < bestPosition) {
                bestPosition = position;
                best = ticket.id();
            }
        }
        return best;
    }

    private PriorityClassRef requireIssuableClass(UUID id) {
        PriorityClassRef priority = tickets.priorityClass(id).orElseThrow(() -> invalid("priority_class_id", "not_found"));
        if (!priority.active()) throw invalid("priority_class_id", "inactive");
        return priority;
    }

    private static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    private static ApiException conflict(String reason) {
        return new ApiException(ErrorCode.CONFLICT, Map.of("reason", reason));
    }
}
