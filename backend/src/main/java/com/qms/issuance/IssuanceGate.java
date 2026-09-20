package com.qms.issuance;

import com.qms.issuance.IssuanceRulesRepository.ServiceRule;
import com.qms.issuance.IssuanceRulesRepository.Settings;
import com.qms.issuance.TicketRepository.ServiceTarget;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The rules that make issuance refuse, each with its own reason (FR-ISS-003): maintenance mode (FR-OPS-043), the rate limits
 * (API-090), business hours, holidays and the channel's cut-off (FR-CFG-020..022), the Service's daily cap (FR-CFG-023), no
 * Agent rostered, and a duplicate active ticket (FR-ISS-004). It runs inside the issuing transaction, so a check that counts
 * (the cap, the rate) is serialised with the insert it guards and two requests cannot both slip under a limit.
 *
 * <p>A refusal is an {@link ApiException} whose {@code details.reason} names the rule, and whose message is written in the
 * caller's language; where an administrator wrote the message (cap reached, maintenance) that wording is used.
 */
@Component
@Profile(Profiles.SERVING)
class IssuanceGate {

    private static final Duration DEVICE_WINDOW = Duration.ofMinutes(1);
    private static final Duration VISITOR_WINDOW = Duration.ofHours(1);

    private final IssuanceRulesRepository rules;

    IssuanceGate(IssuanceRulesRepository rules) {
        this.rules = rules;
    }

    /** Refuses at once what no Service could be issued: maintenance, an unknown visitor, and an actor that is issuing too fast. */
    void beforeService(IssueCommand command, Instant now) {
        Settings settings = rules.settings();
        if (settings.maintenanceEnabled()) {
            throw new ApiException(
                    ErrorCode.UNAVAILABLE, key("maintenance"), new Object[0], Map.of("reason", "maintenance"), settings.maintenanceMessage());
        }
        if (command.visitorId() != null && !rules.visitorExists(command.visitorId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "visitor_id", "code", "not_found"))));
        }
        requireWithinRate(command, now, settings);
    }

    /** What the Service and its Site say about this moment, once the Service is known to be able to issue at all. */
    void forService(ServiceTarget target, IssueCommand command, Instant now) {
        forService(target, command, now, 0);
    }

    /**
     * {@code earlyMinutes} lets a caller join up to that long before the Service's opening time (ticket 42's own remote
     * join, FR-MOB-011's join window); every other caller passes 0 through {@link #forService(ServiceTarget, IssueCommand,
     * Instant)}, which behaves exactly as it always did.
     */
    private void forService(ServiceTarget target, IssueCommand command, Instant now, int earlyMinutes) {
        ZonedDateTime local = now.atZone(ZoneId.of(target.timezone()));
        OpeningHours.check(
                        local,
                        rules.week(target.siteId(), target.serviceId()),
                        rules.holidayOn(target.siteId(), local.toLocalDate()).map(h -> new OpeningHours.Holiday(h.name(), h.halfDay(), h.closeTime())).orElse(null),
                        rules.cutoff(target.siteId(), command.originChannel()),
                        earlyMinutes)
                .ifPresent(refusal -> {
                    throw closed(refusal.reason(), refusal.details(), Map.of());
                });

        ServiceRule rule = rules.serviceRule(target.serviceId());
        if (rule.dailyCap() != null) requireUnderCap(target, rule, local);
        if (rule.requireAgent() && !rules.hasRosteredAgent(target.serviceId())) throw conflict("no_agent_rostered", Map.of());
        if (command.visitorId() != null && !"allow".equals(rule.duplicatePolicy()) && rules.hasActiveTicket(command.visitorId(), target.serviceId())) {
            boolean block = "block".equals(rule.duplicatePolicy());
            // A warning is shown once; the caller who has seen it and still means it asks again with the confirmation.
            if (block || !command.confirmDuplicate()) throw conflict("duplicate_ticket", Map.of("policy", rule.duplicatePolicy()));
        }
    }

    /**
     * The extra checks a remote join must pass beyond a normal issuance (ticket 42, FR-MOB-010..011): the Service's
     * virtual-queue flag must be on, and — once it is — everything {@link #forService} already checks applies too, with
     * its join window in place of the usual "must already be open" (FR-MOB-011's "queue-camping" bound instead), plus the
     * Service's own maximum distance from its Site and the maximum share of the queue a remote ticket may hold.
     */
    void forRemoteJoin(ServiceTarget target, IssueCommand command, Instant now, Double latitude, Double longitude) {
        IssuanceRulesRepository.RemoteRule rule = rules.remoteRule(target.serviceId());
        if (!rule.virtualQueueEnabled()) throw conflict("virtual_queue_disabled", Map.of());

        forService(target, command, now, rule.joinWindowMinutes());

        if (rule.maxDistanceMeters() != null) {
            IssuanceRulesRepository.SiteLocation site = rules.siteLocation(target.siteId()).orElseThrow(() -> conflict("site_location_unset", Map.of()));
            if (latitude == null || longitude == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "latitude", "code", "required"))));
            }
            double meters = GeoDistance.metersBetween(site.latitude(), site.longitude(), latitude, longitude);
            if (meters > rule.maxDistanceMeters()) {
                throw conflict("too_far", Map.of("max_distance_m", rule.maxDistanceMeters(), "distance_m", Math.round(meters)));
            }
        }

        // Has the queue's remote share (before this join) already reached the cap? An empty queue has no share yet, so
        // the very first remote join is always let through — the cap only ever stops a later one, once remote tickets
        // already make up at least their allowed fraction of the queue, until a walk-in ticket widens it again.
        int total = rules.activeQueueCount(target.serviceId());
        int remote = rules.remoteCount(target.serviceId());
        if (total > 0 && (long) remote * 100 >= (long) rule.maxRemoteSharePct() * total) {
            throw conflict("remote_share_full", Map.of("max_remote_share_pct", rule.maxRemoteSharePct()));
        }
    }

    private void requireUnderCap(ServiceTarget target, ServiceRule rule, ZonedDateTime local) {
        rules.lock("cap:" + target.serviceId());
        LocalDate day = local.toLocalDate();
        ZoneId zone = local.getZone();
        int issued = rules.issuedBetween(target.serviceId(), day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant());
        if (issued >= rule.dailyCap()) {
            throw closed("cap_reached", Map.of("daily_cap", rule.dailyCap()), rule.capMessage());
        }
    }

    private void requireWithinRate(IssueCommand command, Instant now, Settings settings) {
        int limit;
        Duration window;
        switch (command.actorType()) {
            case DEVICE -> {
                limit = settings.deviceLimitPerMinute();
                window = DEVICE_WINDOW;
            }
            case VISITOR -> {
                limit = settings.visitorLimitPerHour();
                window = VISITOR_WINDOW;
            }
            default -> {
                return; // Staff at the desk and the system itself are not rate-limited.
            }
        }
        rules.lock("rate:" + command.actorId());
        List<Instant> recent = rules.issuedBy(command.actorId(), command.actorType().wire(), now.minus(window));
        if (recent.size() < limit) return;
        // The next issuance is allowed once enough of the counted ones have aged out of the window.
        Instant free = recent.get(recent.size() - limit).plus(window);
        long seconds = Math.max(1, (Duration.between(now, free).toMillis() + 999) / 1000);
        throw new ApiException(
                ErrorCode.RATE_LIMITED,
                key("rate_limited"),
                new Object[0],
                Map.of("reason", "rate_limited", "retry_after_seconds", seconds, "limit", limit, "window_seconds", window.toSeconds()));
    }

    // ---- refusals ---------------------------------------------------------------------------------------------

    /** The message key of a reason, in every language pack. */
    static String key(String reason) {
        return "issuance.refused." + reason;
    }

    /** A refusal that {@code conflict} carries: the reason, and whatever else the caller needs to explain it. */
    static ApiException conflict(String reason, Map<String, Object> more) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.putAll(more);
        return new ApiException(ErrorCode.CONFLICT, key(reason), new Object[0], details);
    }

    /** The Service is closed for new tickets today (SRS §20.3 {@code service_closed}). */
    static ApiException closed(String reason, Map<String, Object> more, Map<String, String> literal) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.putAll(more);
        return new ApiException(ErrorCode.SERVICE_CLOSED, key(reason), new Object[0], details, literal);
    }
}
