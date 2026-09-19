package com.qms.issuance;

import com.qms.issuance.TicketRepository.OfferedService;
import com.qms.issuance.TicketRepository.ServiceNames;
import com.qms.issuance.TicketRepository.TicketRecord;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.ScopeGuard;
import com.qms.queue.QueueEngine;
import com.qms.queue.QueueReads;
import com.qms.queue.QueueStrategy;
import com.qms.queue.WaitEstimates;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What staff read about tickets and queues: one ticket, a service's queue snapshot, and the services a site offers.
 * Staff means anyone with a dashboard permission in §5.2 (every staff role has one); the token's sites and service
 * groups then limit what they may see (FR-CFG-106). Visitors reading their own ticket arrive with the visitor channels.
 */
@Service
@Profile(Profiles.SERVING)
public class TicketQueries {

    static final String STAFF = "hasAnyAuthority(T(com.qms.platform.security.Authorities).DASHBOARD_VIEW_ALL,"
            + " T(com.qms.platform.security.Authorities).DASHBOARD_VIEW_OWN_GROUPS,"
            + " T(com.qms.platform.security.Authorities).DASHBOARD_VIEW_OWN_GROUPS + ':own')";

    static final String DRY_RUN = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_PRIORITY_ROUTING)";

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private final TicketRepository tickets;
    private final QueueReads queues;
    private final WaitEstimates estimates;
    private final TicketViews views;
    private final ScopeGuard scope;

    TicketQueries(TicketRepository tickets, QueueReads queues, WaitEstimates estimates, TicketViews views, ScopeGuard scope) {
        this.tickets = tickets;
        this.queues = queues;
        this.estimates = estimates;
        this.views = views;
        this.scope = scope;
    }

    @PreAuthorize(STAFF)
    @Transactional(readOnly = true)
    public TicketResponse ticket(UUID id) {
        TicketRecord ticket = tickets.ticket(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(ticket.siteId());
        scope.requireGroup(ticket.groupId());
        return views.of(ticket);
    }

    @PreAuthorize(STAFF)
    @Transactional(readOnly = true)
    public QueueSnapshot queue(UUID serviceId, Integer limit) {
        ServiceNames service = tickets.serviceNames(serviceId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(service.siteId());
        scope.requireGroup(service.groupId());
        int take = limit == null ? DEFAULT_LIMIT : limit;
        if (take < 1 || take > MAX_LIMIT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "limit", "code", "out_of_range"))));
        }
        List<QueueSnapshot.Entry> entries = queues.next(serviceId, take).stream()
                .map(e -> new QueueSnapshot.Entry(e.ticketId(), e.tokenNumber(), e.state(), e.position(), e.originChannel(), e.queuedAt(), classOf(e), e.escalated()))
                .toList();
        // The estimate is what a visitor joining now would be told: everyone waiting is ahead of them (FR-QUE-040).
        int waiting = queues.waitingCount(serviceId);
        return new QueueSnapshot(new NameRef(serviceId, service.names()), service.siteId(), waiting, estimates.ahead(serviceId, waiting), entries);
    }

    /**
     * The whole queue in the order the engine computes it, with each term of each score (FR-QUE-023). {@code strategy}
     * tries another strategy than the group's without changing anything. Needs the priority and routing permission,
     * like the settings it validates.
     */
    @PreAuthorize(DRY_RUN)
    @Transactional(readOnly = true)
    public QueueDryRun dryRun(UUID serviceId, String strategy) {
        ServiceNames service = tickets.serviceNames(serviceId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(service.siteId());
        scope.requireGroup(service.groupId());
        QueueStrategy chosen = null;
        if (strategy != null) {
            chosen = QueueStrategy.fromWire(strategy)
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "strategy", "code", "invalid")))));
        }
        QueueReads.Ordered ordered = queues.ordered(serviceId, chosen);
        List<QueueDryRun.Item> items = ordered.entries().stream().map(TicketQueries::dryRunItem).toList();
        return new QueueDryRun(new NameRef(serviceId, service.names()), service.siteId(), ordered.strategy().wire(), ordered.computedAt(), items.size(), items);
    }

    private static QueueDryRun.Item dryRunItem(QueueReads.Entry e) {
        QueueEngine.Terms t = e.terms();
        var terms = new QueueDryRun.Terms(
                round(t.effectiveWaitMinutes()), round(t.headstartMinutes()), round(t.appointmentBonusMinutes()), round(t.escalationBonusMinutes()),
                round(t.scoreAdjustmentMinutes()), t.adjustmentOverridden());
        return new QueueDryRun.Item(e.ticketId(), e.tokenNumber(), e.state(), e.position(), classOf(e), e.maxWaitMinutes(), e.queuedAt(), terms, round(t.score()), e.escalated());
    }

    private static double round(double minutes) {
        return Math.round(minutes * 100.0) / 100.0;
    }

    private static NameRef classOf(QueueReads.Entry e) {
        return e.priorityClassId() == null ? null : new NameRef(e.priorityClassId(), e.priorityClassNames());
    }

    @PreAuthorize(STAFF)
    @Transactional(readOnly = true)
    public SiteServices siteServices(UUID siteId, String channel) {
        scope.requireSite(siteId);
        TicketRepository.SiteInfo site = tickets.site(siteId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (channel != null && !Channels.ALL.contains(channel)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "channel", "code", "invalid"))));
        }
        List<SiteServices.Item> items = tickets.offeredServices(siteId, channel).stream().map(this::item).toList();
        return new SiteServices(siteId, site.defaultLanguage(), items);
    }

    private SiteServices.Item item(OfferedService s) {
        return new SiteServices.Item(
                s.id(), s.names(), new NameRef(s.groupId(), s.groupNames()), s.tokenPrefix(), s.icon(), s.displayOrder(), s.waitingCount(), estimates.ahead(s.id(), s.waitingCount()));
    }
}
