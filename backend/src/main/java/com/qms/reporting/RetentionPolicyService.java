package com.qms.reporting;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code /retention/policies} (ticket 53, SRS §16.3, §25.4-25.5; FR-RPT-021/022, FR-SEC-032/043): how long each
 * data class is kept, and (for {@code ticket_detail} alone) whether it is purged outright or reduced to an
 * anonymised aggregate once its own retention passes. Gated on {@code audit:read} — the same System/Org Admin only
 * permission {@code DomainReportService} already treats as "stricter... System/Org Admin only" for the {@code
 * audit} report key — since this configures the retention of the audit log itself alongside the reporting store,
 * and there is no dedicated permission for it in the SRS §5.2 matrix to reuse instead (composing from an existing
 * permission the way {@code ReportScheduleService.MANAGE} already composes two, rather than adding a new {@link
 * com.qms.platform.security.Permission} the closed §5.2 matrix does not itself list).
 */
@Service
@Profile(Profiles.SERVING)
class RetentionPolicyService {

    static final String MANAGE = "hasAuthority(T(com.qms.platform.security.Authorities).AUDIT_READ)";

    private static final int MIN_MONTHS = 1;
    private static final int MAX_MONTHS = 1200;

    private final RetentionPolicyRepository repo;
    private final AuditWriter audit;
    private final CurrentUser currentUser;
    private final Clock clock;

    RetentionPolicyService(RetentionPolicyRepository repo, AuditWriter audit, CurrentUser currentUser, Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @PreAuthorize(MANAGE)
    @Transactional(readOnly = true)
    List<RetentionPolicyView> list() {
        return repo.list().stream().map(RetentionPolicyService::view).toList();
    }

    @PreAuthorize(MANAGE)
    @Transactional
    RetentionPolicyView update(String dataClassWire, RetentionPolicyRequest request) {
        RetentionDataClass dataClass = RetentionDataClass.fromWire(dataClassWire).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        RetentionPolicyRepository.Row existing =
                repo.find(dataClass.wire()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        int retentionMonths = validateMonths(request.retentionMonths());
        String mode = validateMode(dataClass, request.mode(), existing.mode());

        AuthenticatedUser user = currentUser.require();
        Instant now = clock.instant();
        repo.update(dataClass.wire(), retentionMonths, mode, user.userId(), now);

        audit.record(AuditEvent.of("retention.policy_changed", "retention_policy", null)
                .withBefore(Map.of("data_class", existing.dataClass(), "retention_months", existing.retentionMonths(), "mode", existing.mode()))
                .withAfter(Map.of("data_class", dataClass.wire(), "retention_months", retentionMonths, "mode", mode)));

        return view(repo.find(dataClass.wire()).orElseThrow());
    }

    private static int validateMonths(Integer months) {
        if (months == null) throw fail("retention_months", "required");
        if (months < MIN_MONTHS || months > MAX_MONTHS) throw fail("retention_months", "out_of_range");
        return months;
    }

    private static String validateMode(RetentionDataClass dataClass, String requestedMode, String currentMode) {
        if (requestedMode == null) return currentMode;
        if (!"purge".equals(requestedMode) && !"anonymize".equals(requestedMode)) throw fail("mode", "unknown_value");
        if (dataClass != RetentionDataClass.TICKET_DETAIL && !"purge".equals(requestedMode)) throw fail("mode", "not_configurable");
        return requestedMode;
    }

    private static RetentionPolicyView view(RetentionPolicyRepository.Row row) {
        return new RetentionPolicyView(row.dataClass(), row.retentionMonths(), row.mode(), row.updatedAt(), row.updatedBy());
    }

    private static ApiException fail(String field, String code) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("field", field, "code", code));
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", fields));
    }
}
