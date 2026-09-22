package com.qms.issuance.setup;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.IssueCommand;
import com.qms.issuance.TicketResponse;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The first-run setup wizard's own state and its test token (SRS §26.2, FR-OPS-010). Every step but the test token
 * is read straight off the real tables the ordinary admin screens (sites, catalogue, users, devices) already write
 * to, so this never drifts from what actually exists. Go-live is refused until every step, including a real Ticket
 * issued, printed, called and announced end to end, is true.
 */
@Service
@Profile(Profiles.SERVING)
public class SetupWizardService {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";
    static final String GO_LIVE_KEY = "go_live";

    private final JdbcTemplate jdbc;
    private final VerticalProfileService profiles;
    private final SetupTestTicketRepository testTickets;
    private final SystemSettingRepository settings;
    private final IssuanceService issuance;
    private final AuditWriter audit;
    private final Clock clock;

    SetupWizardService(
            JdbcTemplate jdbc,
            VerticalProfileService profiles,
            SetupTestTicketRepository testTickets,
            SystemSettingRepository settings,
            IssuanceService issuance,
            AuditWriter audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.testTickets = testTickets;
        this.settings = settings;
        this.issuance = issuance;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public SetupState state() {
        Optional<ActiveProfile> active = profiles.active();
        boolean profileApplied = active.isPresent();
        boolean orgAndSites = count("site") > 0;
        boolean zonesAndCounters = count("zone") > 0 && count("counter") > 0;
        boolean servicesAndNumbering = count("service") > 0;
        boolean usersAndRoles = count("users") > 1; // more than the bootstrap admin alone
        boolean devicesRegistered = count("device") > 0;

        SetupState.TestTokenState testToken = testTickets.latest().map(t -> new SetupState.TestTokenState(
                        true, t.printedAt() != null, isCalledOrBeyond(t.state()), t.announced(), t.ticketId().toString(), t.tokenNumber()))
                .orElse(SetupState.TestTokenState.NONE);

        boolean goLiveReady = profileApplied && orgAndSites && zonesAndCounters && servicesAndNumbering && usersAndRoles && devicesRegistered
                && testToken.issued() && testToken.printed() && testToken.called() && testToken.announced();

        Instant goLiveAt = settings.get(GO_LIVE_KEY).map(v -> Instant.parse(String.valueOf(v.get("at")))).orElse(null);

        return new SetupState(
                profileApplied, active.orElse(null), orgAndSites, zonesAndCounters, servicesAndNumbering, usersAndRoles, devicesRegistered, testToken, goLiveReady, goLiveAt);
    }

    /** A ticket the once-called and once-completed states already move a called ticket through (FR-QUE-030..032). */
    private static boolean isCalledOrBeyond(String ticketState) {
        return List.of("called", "serving", "held", "completed").contains(ticketState);
    }

    private int count(String table) {
        Integer result = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return result == null ? 0 : result;
    }

    /** Issues a real Ticket through the normal pipeline, flagged as the wizard's own test token (FR-OPS-010). */
    @PreAuthorize(PERMISSION)
    @Transactional
    public TicketResponse issueTestToken(UUID serviceId, UUID actorId) {
        if (serviceId == null) throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "service_id", "code", "required"))));
        IssueCommand command = new IssueCommand(serviceId, Channels.RECEPTION, actorId, ActorType.STAFF, null, null);
        TicketResponse response = issuance.issue(command);
        Instant now = clock.instant();
        testTickets.insert(UUID.randomUUID(), response.id(), now, actorId);
        audit.record(AuditEvent.of("setup.test_token.issued", "ticket", response.id()).withAfter(Map.of("token_number", response.tokenNumber())));
        return response;
    }

    /** The admin's confirmation that the test token's printed rendering (ticket 27) actually came out of the printer. */
    @PreAuthorize(PERMISSION)
    @Transactional
    public void confirmPrinted(UUID ticketId, UUID actorId) {
        testTickets.byTicketId(ticketId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        testTickets.confirmPrinted(ticketId, clock.instant(), actorId);
        audit.record(AuditEvent.of("setup.test_token.printed", "ticket", ticketId));
    }

    /** FR-OPS-010: refused until every step, and the test token's whole issued/printed/called/announced chain, is true. */
    @PreAuthorize(PERMISSION)
    @Transactional
    public Instant goLive(UUID actorId) {
        SetupState state = state();
        Optional<Instant> already = settings.get(GO_LIVE_KEY).map(v -> Instant.parse(String.valueOf(v.get("at"))));
        if (already.isPresent()) return already.get();

        if (!state.goLiveReady()) {
            List<String> missing = missingSteps(state);
            throw new ApiException(ErrorCode.CONFLICT, "setup.refused.incomplete", new Object[0], Map.of("reason", "setup_incomplete", "missing", missing));
        }

        Instant now = clock.instant();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("at", now.toString());
        value.put("by", actorId == null ? null : actorId.toString());
        settings.set(GO_LIVE_KEY, value, actorId, now);
        audit.record(AuditEvent.of("setup.go_live", "installation", null));
        return now;
    }

    private static List<String> missingSteps(SetupState state) {
        List<String> missing = new ArrayList<>();
        if (!state.profileApplied()) missing.add("profile_applied");
        if (!state.orgAndSites()) missing.add("org_and_sites");
        if (!state.zonesAndCounters()) missing.add("zones_and_counters");
        if (!state.servicesAndNumbering()) missing.add("services_and_numbering");
        if (!state.usersAndRoles()) missing.add("users_and_roles");
        if (!state.devicesRegistered()) missing.add("devices_registered");
        if (!state.testToken().issued()) missing.add("test_token_issued");
        if (!state.testToken().printed()) missing.add("test_token_printed");
        if (!state.testToken().called()) missing.add("test_token_called");
        if (!state.testToken().announced()) missing.add("test_token_announced");
        return missing;
    }
}
