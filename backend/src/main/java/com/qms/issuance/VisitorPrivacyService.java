package com.qms.issuance;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code /visitors/{id}/export} and {@code /visitors/{id}/anonymize} (FR-SEC-031, ticket 54): both executable only
 * by an Org Admin, gated the same as every other org-wide admin screen ({@code config:org_sites_zones}, §5.2 has no
 * dedicated permission for this either). An export is itself a PII export and an anonymisation a consent/retention
 * -adjacent change, both of which FR-SEC-040 requires in the audit log; the export's audit entry never carries the
 * exported values themselves, only that the export happened and of whom.
 */
@Service
@Profile(Profiles.SERVING)
class VisitorPrivacyService {

    static final String MANAGE = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";

    private final VisitorPrivacyRepository repo;
    private final AuditWriter audit;
    private final Clock clock;

    VisitorPrivacyService(VisitorPrivacyRepository repo, AuditWriter audit, Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize(MANAGE)
    @Transactional
    VisitorExportResponse export(UUID visitorId) {
        VisitorPrivacyRepository.VisitorRecord visitor = repo.find(visitorId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        var tickets = repo.ticketsOf(visitorId).stream()
                .map(t -> new VisitorExportResponse.TicketEntry(
                        t.id(), t.tokenNumber(), t.state(), t.serviceNames(), t.siteId(), t.purposeNote(), t.issuedAt(), t.closedAt()))
                .toList();
        var notificationConsent = repo.notificationConsent(visitorId).map(VisitorPrivacyService::consent).orElse(null);
        var retentionConsent = repo.retentionConsent(visitorId).map(VisitorPrivacyService::consent).orElse(null);

        audit.record(AuditEvent.of("visitor.exported", "visitor", visitorId).withReason("org_admin_export"));

        return new VisitorExportResponse(
                visitor.id(), visitor.externalCode(), visitor.name(), visitor.category(), visitor.phone(), visitor.email(),
                visitor.preferredLanguage(), visitor.createdAt(), visitor.anonymizedAt(), tickets, notificationConsent, retentionConsent);
    }

    @PreAuthorize(MANAGE)
    @Transactional
    VisitorAnonymizeResponse anonymize(UUID visitorId) {
        repo.find(visitorId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Instant now = clock.instant();
        int ticketsAnonymized = repo.anonymize(visitorId, now);

        audit.record(AuditEvent.of("visitor.anonymized", "visitor", visitorId)
                .withAfter(Map.of("tickets_anonymized", ticketsAnonymized))
                .withReason("org_admin_deletion_request"));

        return new VisitorAnonymizeResponse(visitorId, now, ticketsAnonymized);
    }

    private static VisitorExportResponse.ConsentEntry consent(VisitorPrivacyRepository.ConsentRecord row) {
        return new VisitorExportResponse.ConsentEntry(row.granted(), row.consentTextVersion(), row.recordedAt());
    }
}
