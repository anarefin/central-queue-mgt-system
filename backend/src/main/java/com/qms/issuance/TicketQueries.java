package com.qms.issuance;

import com.qms.issuance.TicketRepository.OfferedService;
import com.qms.issuance.TicketRepository.ServiceNames;
import com.qms.issuance.TicketRepository.TicketRecord;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.ScopeGuard;
import com.qms.queue.QueueReads;
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

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private final TicketRepository tickets;
    private final QueueReads queues;
    private final TicketViews views;
    private final ScopeGuard scope;

    TicketQueries(TicketRepository tickets, QueueReads queues, TicketViews views, ScopeGuard scope) {
        this.tickets = tickets;
        this.queues = queues;
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
                .map(e -> new QueueSnapshot.Entry(e.ticketId(), e.tokenNumber(), e.state(), e.position(), e.originChannel(), e.queuedAt()))
                .toList();
        return new QueueSnapshot(new NameRef(serviceId, service.names()), service.siteId(), queues.waitingCount(serviceId), null, entries);
    }

    @PreAuthorize(STAFF)
    @Transactional(readOnly = true)
    public SiteServices siteServices(UUID siteId, String channel) {
        scope.requireSite(siteId);
        TicketRepository.SiteInfo site = tickets.site(siteId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (channel != null && !Channels.ALL.contains(channel)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "channel", "code", "invalid"))));
        }
        List<SiteServices.Item> items = tickets.offeredServices(siteId, channel).stream().map(TicketQueries::item).toList();
        return new SiteServices(siteId, site.defaultLanguage(), items);
    }

    private static SiteServices.Item item(OfferedService s) {
        return new SiteServices.Item(s.id(), s.names(), new NameRef(s.groupId(), s.groupNames()), s.tokenPrefix(), s.icon(), s.displayOrder(), s.waitingCount(), null);
    }
}
