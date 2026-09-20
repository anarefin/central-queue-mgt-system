package com.qms.platform.realtime;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The anonymous visitor of one Ticket (§20.2, FR-SEC-033, ticket 37): proved the ticket's own secret, not a JWT, so the
 * connection carries no expiry ({@link Connection#expiresAt()} is null for anything but a {@code JwtAuthenticationToken})
 * and no authority beyond reading that one ticket's own topic ({@code TicketTopics}). It is never told apart from another
 * principal by role or scope, only by which ticket id it names.
 */
public final class TicketPrincipal implements Authentication {

    private final String ticketId;
    private boolean authenticated = true;

    public TicketPrincipal(String ticketId) {
        this.ticketId = ticketId;
    }

    public String ticketId() {
        return ticketId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("TICKET_VISITOR"));
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getDetails() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return ticketId;
    }

    @Override
    public boolean isAuthenticated() {
        return authenticated;
    }

    @Override
    public void setAuthenticated(boolean isAuthenticated) {
        this.authenticated = isAuthenticated;
    }

    /** The subject a {@code principal.changed} or a {@code reauth} would name; a ticket-secret connection never receives either. */
    @Override
    public String getName() {
        return "ticket:" + ticketId;
    }
}
