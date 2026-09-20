package com.qms.issuance;

import com.qms.issuance.TicketRepository.TicketRecord;
import com.qms.platform.Profiles;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * What a visitor's own ticket id plus its {@code X-Ticket-Secret} proves (§20.2, FR-SEC-033, ticket 37): the ticket, or
 * nothing when the id is unknown or the presented value does not match. The comparison is constant-time so a wrong-length
 * or wrong-value credential takes the same time either way, and a token number alone (with no credential) never resolves
 * anything.
 */
@Component
@Profile(Profiles.SERVING)
class TicketCredentialAccess {

    private final TicketRepository tickets;

    TicketCredentialAccess(TicketRepository tickets) {
        this.tickets = tickets;
    }

    Optional<TicketRecord> verify(UUID ticketId, String presented) {
        if (ticketId == null || presented == null || presented.isBlank()) return Optional.empty();
        Optional<String> hash = tickets.secretHash(ticketId);
        if (hash.isEmpty()) return Optional.empty();
        boolean matches = MessageDigest.isEqual(
                IssuanceService.hash(presented).getBytes(StandardCharsets.UTF_8), hash.get().getBytes(StandardCharsets.UTF_8));
        return matches ? tickets.ticket(ticketId) : Optional.empty();
    }
}
