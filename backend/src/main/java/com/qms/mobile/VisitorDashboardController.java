package com.qms.mobile;

import com.qms.platform.Profiles;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /visitors/me/*} (SRS §13.1, FR-MOB-002, ticket 41): a registered visitor's own active tickets,
 * appointment history and saved sites. Every method carries its own permission here (API-016); the service checks it
 * again and, since every read and write here already acts on the caller's own id alone, there is no further
 * object-level check to make.
 */
@RestController
@Profile(Profiles.SERVING)
public class VisitorDashboardController {

    private static final String VISITOR = "hasRole('VISITOR')";

    private final VisitorDashboardService dashboard;

    VisitorDashboardController(VisitorDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @PreAuthorize(VISITOR)
    @GetMapping("/visitors/me/tickets")
    public Items<TicketSummaryView> tickets() {
        return dashboard.myTickets();
    }

    @PreAuthorize(VISITOR)
    @GetMapping("/visitors/me/appointments")
    public Items<AppointmentSummaryView> appointments() {
        return dashboard.myAppointments();
    }

    @PreAuthorize(VISITOR)
    @GetMapping("/visitors/me/saved-sites")
    public Items<SavedSiteView> savedSites() {
        return dashboard.mySavedSites();
    }

    @PreAuthorize(VISITOR)
    @PostMapping("/visitors/me/saved-sites/{siteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveSite(@PathVariable UUID siteId) {
        dashboard.saveSite(siteId);
    }

    @PreAuthorize(VISITOR)
    @DeleteMapping("/visitors/me/saved-sites/{siteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsaveSite(@PathVariable UUID siteId) {
        dashboard.unsaveSite(siteId);
    }
}
