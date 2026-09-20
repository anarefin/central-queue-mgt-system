package com.qms.platform.realtime;

import java.util.Optional;

/**
 * Turns a ticket id and its secret, offered on the hub's own handshake (SRS §20.2, FR-SEC-033, ticket 37), into the
 * connection's authentication, or empty when the id is unknown or the secret does not match. Implemented by the context
 * that owns the ticket (issuance), so the hub and its handshake never need to know what a ticket is (the same seam
 * {@link TokenVerifier} and {@code TopicSource} already use).
 */
public interface TicketPrincipalVerifier {

    Optional<TicketPrincipal> verify(String ticketId, String secret);
}
