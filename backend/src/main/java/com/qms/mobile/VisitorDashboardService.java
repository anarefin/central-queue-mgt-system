package com.qms.mobile;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A registered visitor's own "my account" read model and saved sites (ticket 41, FR-MOB-002). Every method acts on
 * the caller's own visitor id only, taken from their own JWT (never a client-supplied id): there is nothing to scope
 * beyond that, since a visitor's token already names exactly one record. */
@Service
@Profile(Profiles.SERVING)
class VisitorDashboardService {

    private static final String VISITOR = "hasRole('VISITOR')";

    private final VisitorDashboardRepository repository;
    private final CurrentUser currentUser;
    private final AuditWriter audit;
    private final Clock clock;

    VisitorDashboardService(VisitorDashboardRepository repository, CurrentUser currentUser, AuditWriter audit, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize(VISITOR)
    @Transactional(readOnly = true)
    Items<TicketSummaryView> myTickets() {
        var rows = repository.activeTickets(visitorId());
        return new Items<>(rows.stream()
                .map(r -> new TicketSummaryView(r.id(), r.tokenNumber(), r.state(), r.serviceId(), r.serviceNames(), r.siteId(), r.siteName(), r.issuedAt().toString()))
                .toList());
    }

    @PreAuthorize(VISITOR)
    @Transactional(readOnly = true)
    Items<AppointmentSummaryView> myAppointments() {
        var rows = repository.appointmentHistory(visitorId());
        return new Items<>(rows.stream()
                .map(r -> new AppointmentSummaryView(
                        r.id(), r.referenceCode(), r.serviceId(), r.serviceNames(), r.siteId(), r.siteName(), r.slotDate().toString(), r.slotStart().toString(), r.slotEnd().toString(),
                        r.state()))
                .toList());
    }

    @PreAuthorize(VISITOR)
    @Transactional(readOnly = true)
    Items<SavedSiteView> mySavedSites() {
        var rows = repository.savedSites(visitorId());
        return new Items<>(rows.stream().map(r -> new SavedSiteView(r.siteId(), r.siteName())).toList());
    }

    @PreAuthorize(VISITOR)
    @Transactional
    void saveSite(UUID siteId) {
        if (siteId == null || !repository.siteExists(siteId)) throw new ApiException(ErrorCode.NOT_FOUND);
        UUID visitorId = visitorId();
        repository.saveSite(visitorId, siteId, clock.instant());
        audit.record(AuditEvent.of("visitor.site_saved", "visitor", visitorId).withAfter(Map.of("site_id", siteId.toString())));
    }

    @PreAuthorize(VISITOR)
    @Transactional
    void unsaveSite(UUID siteId) {
        UUID visitorId = visitorId();
        repository.unsaveSite(visitorId, siteId);
        audit.record(AuditEvent.of("visitor.site_unsaved", "visitor", visitorId).withAfter(Map.of("site_id", String.valueOf(siteId))));
    }

    private UUID visitorId() {
        return currentUser.require().userId();
    }
}
