package com.qms.issuance;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.configuration.privacy.VisitorFieldConfigService;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Visitor directory search and walk-in registration for Reception (SRS §8.3, §22.2). Lookup goes through {@link
 * VisitorDirectoryGateway}, which is the seam FR-INT-012 and FR-INT-013 describe; registration writes the local
 * {@code visitor} table directly, since local storage is what registration creates.
 */
@Service
@Profile(Profiles.SERVING)
class VisitorService {

    /** Excludes characters that are easy to misread on a printed pass: 0/O, 1/I. */
    private static final char[] PASS_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int PASS_LENGTH = 8;
    private static final int PASS_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final VisitorDirectoryGateway gateway;
    private final VisitorRepository visitors;
    private final VisitorProperties properties;
    private final VisitorFieldConfigService fieldConfig;
    private final AuditWriter audit;
    private final Clock clock;

    VisitorService(
            VisitorDirectoryGateway gateway,
            VisitorRepository visitors,
            VisitorProperties properties,
            VisitorFieldConfigService fieldConfig,
            AuditWriter audit,
            Clock clock) {
        this.gateway = gateway;
        this.visitors = visitors;
        this.properties = properties;
        this.fieldConfig = fieldConfig;
        this.audit = audit;
        this.clock = clock;
    }

    /** {@code GET /visitors/lookup} (FR-ISS-020, FR-INT-012). Never blocks the caller past the directory's hard timeout. */
    VisitorLookupResponse lookup(String query) {
        VisitorDirectory.Match match = gateway.lookup(query).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return new VisitorLookupResponse(match.id(), match.externalCode(), match.name(), match.category(), match.phone(), match.flags());
    }

    /**
     * {@code GET /kiosk/visitors/identify} (ticket 26, FR-ISS-013, FR-ISS-014): the same directory a typed code, a
     * scanned QR payload or a dialled mobile number all resolve through, but only name and category come back — the
     * kiosk is the visitor's own device, not staff, so nothing else on the visitor record may reach it.
     */
    KioskVisitorIdentifyResponse identifyForKiosk(String query) {
        VisitorDirectory.Match match = gateway.lookup(query).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        // FR-SEC-020's "Kiosk confirmation | Name, category" row, now Org Admin-configurable (ticket 54): a field
        // turned off here is left out of the response entirely, not merely hidden by the kiosk's own screen.
        return new KioskVisitorIdentifyResponse(
                match.id(),
                fieldConfig.isVisible("kiosk_confirmation", "name") ? match.name() : null,
                fieldConfig.isVisible("kiosk_confirmation", "category") ? match.category() : null);
    }

    /** {@code POST /visitors} (FR-ISS-021): a minimal record and a pass reference for an unknown walk-in. */
    @Transactional
    VisitorRegistrationResponse register(RegisterVisitorRequest request) {
        if (request == null || blank(request.name()) == null) throw invalid("name", "required");
        if (blank(request.phone()) == null) throw invalid("phone", "required");
        // FR-SEC-023, ticket 54: an Org Admin's runtime `capture` field config (com.qms.configuration.privacy) is a
        // second, narrower gate on top of the deploy-time `qms.visitor.registration-fields` capture() already
        // enforces — a field reaches the insert only when BOTH allow it, so turning one off at either layer is
        // enough to stop it being captured or retained.
        Captured capturedByConfig = capture(request, properties);
        Captured captured = new Captured(
                capturedByConfig.name(),
                capturedByConfig.phone(),
                fieldConfig.isVisible("capture", "email") ? capturedByConfig.email() : null,
                fieldConfig.isVisible("capture", "category") ? capturedByConfig.category() : null,
                fieldConfig.isVisible("capture", "purpose") ? capturedByConfig.purpose() : null);

        Instant now = clock.instant();
        Insert inserted = insertWithFreshPassReference(captured.name(), captured.phone(), captured.email(), captured.category(), now);
        audit.record(AuditEvent.of("visitor.registered", "visitor", inserted.id()).withAfter(Map.of("pass_reference", inserted.passReference())));
        return new VisitorRegistrationResponse(inserted.id(), inserted.passReference(), captured.name(), captured.phone(), captured.email(), captured.category(), captured.purpose());
    }

    /**
     * What FR-SEC-023 keeps from a registration request: {@code name} and {@code phone} always (they are the minimum
     * record itself), the rest only when {@code properties} turns that field on. A field left out is not merely
     * hidden from the response: it never reaches {@link #register}'s insert, so it is never retained either.
     */
    static Captured capture(RegisterVisitorRequest request, VisitorProperties properties) {
        return new Captured(
                blank(request.name()),
                blank(request.phone()),
                properties.captures("email") ? blank(request.email()) : null,
                properties.captures("category") ? blank(request.category()) : null,
                properties.captures("purpose") ? blank(request.purpose()) : null);
    }

    record Captured(String name, String phone, String email, String category, String purpose) {}

    private record Insert(UUID id, String passReference) {}

    private Insert insertWithFreshPassReference(String name, String phone, String email, String category, Instant now) {
        DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < PASS_ATTEMPTS; attempt++) {
            String passReference = newPassReference();
            try {
                return new Insert(visitors.insert(passReference, name, phone, email, category, now), passReference);
            } catch (DataIntegrityViolationException e) {
                last = e; // The pass reference collided with an existing external_code; try another (astronomically rare).
            }
        }
        throw last;
    }

    private static String newPassReference() {
        StringBuilder sb = new StringBuilder("V-");
        for (int i = 0; i < PASS_LENGTH; i++) sb.append(PASS_ALPHABET[RANDOM.nextInt(PASS_ALPHABET.length)]);
        return sb.toString();
    }

    private static String blank(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }
}
