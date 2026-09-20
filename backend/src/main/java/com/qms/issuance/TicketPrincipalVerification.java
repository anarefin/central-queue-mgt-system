package com.qms.issuance;

import com.qms.platform.Profiles;
import com.qms.platform.realtime.TicketPrincipal;
import com.qms.platform.realtime.TicketPrincipalVerifier;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The realtime hub's own use of {@link TicketCredentialAccess}, for a visitor's WebSocket handshake (ticket 37, §20.2, §21.1). */
@Component
@Profile(Profiles.SERVING)
class TicketPrincipalVerification implements TicketPrincipalVerifier {

    private final TicketCredentialAccess access;

    TicketPrincipalVerification(TicketCredentialAccess access) {
        this.access = access;
    }

    @Override
    public Optional<TicketPrincipal> verify(String ticketId, String secret) {
        UUID id;
        try {
            id = UUID.fromString(ticketId);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return Optional.empty();
        }
        return access.verify(id, secret).map(ticket -> new TicketPrincipal(ticket.id().toString()));
    }
}
