package com.qms.session;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Counter sessions over HTTP (SRS §20.4). Ticket actions take the ticket's version in {@code If-Match} (§20.1); leaving
 * it out acts on whatever ticket the session holds now. A session with several tickets in progress (parallel serving, FR-AGT-011)
 * says which with {@code ticket_id}; left out, it is the one called first. Each method carries its permission here, where the build-time
 * check looks for it (FR-CFG-108), and the service checks it again along with ownership (API-016, FR-CFG-105).
 */
@RestController
@Profile(Profiles.SERVING)
public class SessionController {

    private final SessionService service;

    SessionController(SessionService service) {
        this.service = service;
    }

    @PreAuthorize(SessionService.OPEN_CLOSE)
    @GetMapping("/sessions/options")
    public CounterOptions options() {
        return service.options();
    }

    @PreAuthorize(SessionService.EITHER)
    @GetMapping("/sessions/current")
    public SessionResponse current() {
        return service.current();
    }

    @PreAuthorize(SessionService.OPEN_CLOSE)
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse open(@RequestBody OpenSessionRequest request) {
        return service.open(request);
    }

    @PreAuthorize(SessionService.OPEN_CLOSE)
    @DeleteMapping("/sessions/{id}")
    public SessionResponse close(@PathVariable UUID id) {
        return service.close(id);
    }

    @PreAuthorize(SessionService.SERVE)
    @PostMapping("/sessions/{id}/next")
    public SessionResponse next(@PathVariable UUID id) {
        return service.callNext(id);
    }

    /** A specific waiting ticket, called out of order with a mandatory reason (FR-AGT-012). */
    @PreAuthorize(SessionService.SERVE)
    @PostMapping("/sessions/{id}/call")
    public SessionResponse callTicket(@PathVariable UUID id, @RequestBody(required = false) CallTicketRequest request) {
        return service.callTicket(id, request);
    }

    @PreAuthorize(SessionService.SERVE)
    @PostMapping("/sessions/{id}/reannounce")
    public SessionResponse reannounce(
            @PathVariable UUID id, @RequestParam(name = "ticket_id", required = false) UUID ticketId, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.reannounce(id, ticketId, version(ifMatch));
    }

    @PreAuthorize(SessionService.SERVE)
    @PostMapping("/sessions/{id}/miss")
    public SessionResponse miss(
            @PathVariable UUID id, @RequestParam(name = "ticket_id", required = false) UUID ticketId, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.miss(id, ticketId, version(ifMatch));
    }

    /** The answer to the call timeout prompt: the called ticket goes back to the queue with its original wait (FR-QUE-032). */
    @PreAuthorize(SessionService.SERVE)
    @PostMapping("/sessions/{id}/return")
    public SessionResponse returnToQueue(
            @PathVariable UUID id, @RequestParam(name = "ticket_id", required = false) UUID ticketId, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.returnToQueue(id, ticketId, version(ifMatch));
    }

    /** F8: holds the serving ticket, or, given a {@code ticket_id}, resumes that held ticket (FR-AGT-013). */
    @PreAuthorize(SessionService.SERVE)
    @PostMapping("/sessions/{id}/hold")
    public SessionResponse hold(
            @PathVariable UUID id,
            @RequestParam(name = "ticket_id", required = false) UUID ticketId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) HoldRequest request) {
        return service.hold(id, ticketId, request, version(ifMatch));
    }

    /** F9: starts the break named by {@code break_type_id}, or, with none, ends the break the session is on (FR-AGT-020, FR-AGT-021). */
    @PreAuthorize(SessionService.OPEN_CLOSE)
    @PostMapping("/sessions/{id}/break")
    public SessionResponse takeBreak(@PathVariable UUID id, @RequestBody(required = false) BreakRequest request) {
        return service.takeBreak(id, request);
    }

    /** An Org or Team Admin closes a stale session; its tickets return to the front of their queues (FR-AGT-002). */
    @PreAuthorize(SessionService.FORCE_CLOSE)
    @PostMapping("/sessions/{id}/force-close")
    public SessionResponse forceClose(@PathVariable UUID id, @RequestBody(required = false) ForceCloseRequest request) {
        return service.forceClose(id, request);
    }

    @PreAuthorize(SessionService.SERVE)
    @PostMapping("/sessions/{id}/serve")
    public SessionResponse serve(
            @PathVariable UUID id, @RequestParam(name = "ticket_id", required = false) UUID ticketId, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.startService(id, ticketId, version(ifMatch));
    }

    @PreAuthorize(SessionService.SERVE)
    @PostMapping("/sessions/{id}/complete")
    public SessionResponse complete(
            @PathVariable UUID id,
            @RequestParam(name = "ticket_id", required = false) UUID ticketId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) CompleteRequest request) {
        return service.complete(id, ticketId, request, version(ifMatch));
    }

    /** The ticket version in an {@code If-Match} header: a number, optionally quoted or weak ({@code W/"3"}). */
    static Integer version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) return null;
        String value = ifMatch.strip();
        if (value.startsWith("W/")) value = value.substring(2);
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
        try {
            int version = Integer.parseInt(value);
            if (version >= 0) return version;
        } catch (NumberFormatException ignored) {
            // falls through to the refusal below
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "If-Match", "code", "invalid"))));
    }
}
