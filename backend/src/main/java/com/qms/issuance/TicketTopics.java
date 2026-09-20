package com.qms.issuance;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.realtime.TicketPrincipal;
import com.qms.platform.realtime.TopicSource;
import com.qms.platform.realtime.Topics;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The {@code ticket:{ticket_id}} topic (§21.2, ticket 37, FR-MOB-013): a visitor's own live position, estimate and now-
 * serving token. Only the {@link TicketPrincipal} that ticket's own secret proved may subscribe (FR-SEC-033); there is no
 * staff reach here, unlike every other topic, because staff already have {@code queue:} and {@code counter:} for the same
 * Service.
 */
@Component
class TicketTopics implements TopicSource {

    private final TicketRepository tickets;
    private final VisitorTicketViews views;

    TicketTopics(TicketRepository tickets, VisitorTicketViews views) {
        this.tickets = tickets;
        this.views = views;
    }

    @Override
    public boolean handles(String topic) {
        return topic.startsWith(Topics.TICKET);
    }

    @Override
    public void authorize(String topic) {
        UUID ticketId = id(topic);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof TicketPrincipal principal) || !principal.ticketId().equals(ticketId.toString())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (tickets.ticket(ticketId).isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND);
    }

    @Override
    public Map<String, Object> snapshot(String topic) {
        UUID ticketId = id(topic);
        var ticket = tickets.ticket(ticketId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return views.view(ticket);
    }

    private static UUID id(String topic) {
        try {
            return UUID.fromString(topic.substring(Topics.TICKET.length()));
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "topic", "code", "invalid"))));
        }
    }
}
