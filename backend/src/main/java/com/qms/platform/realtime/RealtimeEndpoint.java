package com.qms.platform.realtime;

import com.qms.platform.ApiPathConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;

/** Where the hub listens and how a browser presents its credentials to it (SRS §21.1, §20.2). */
public final class RealtimeEndpoint {

    /** {@code wss://<host>/api/v1/stream}. */
    public static final String PATH = ApiPathConfig.BASE_PATH + "/stream";
    /** The one subprotocol the hub speaks. */
    public static final String PROTOCOL = "qms.v1";
    /** A browser cannot set {@code Authorization} on a WebSocket, so it offers the token as a second subprotocol. */
    static final String TOKEN_PROTOCOL_PREFIX = "bearer.";
    /**
     * The same trick for a ticket id and its secret (ticket 37, FR-SEC-033): {@code ticket.<id>.<secret>}, neither in a
     * URL nor in a log (SRS §21.1, API-018), the way a staff or device token already rides the subprotocol offer.
     */
    static final String TICKET_PROTOCOL_PREFIX = "ticket.";

    private RealtimeEndpoint() {}

    /**
     * The access token a browser offered as {@code Sec-WebSocket-Protocol: qms.v1, bearer.<token>}, on a connection to the
     * hub only. Anything else is left to the standard {@code Authorization} header, which non-browser clients use.
     */
    public static Optional<String> offeredToken(HttpServletRequest request) {
        if (!PATH.equals(request.getRequestURI())) return Optional.empty();
        String header = request.getHeader("Sec-WebSocket-Protocol");
        if (header == null) return Optional.empty();
        return offers(List.of(header))
                .filter(offer -> offer.startsWith(TOKEN_PROTOCOL_PREFIX) && offer.length() > TOKEN_PROTOCOL_PREFIX.length())
                .map(offer -> offer.substring(TOKEN_PROTOCOL_PREFIX.length()))
                .findFirst();
    }

    /** A ticket id and its secret, as an anonymous visitor's browser offers them on the handshake itself (ticket 37). */
    record TicketCredentials(String ticketId, String secret) {}

    /**
     * The ticket id and secret a browser offered as {@code Sec-WebSocket-Protocol: qms.v1, ticket.<id>.<secret>}. The id
     * cannot itself contain a {@code .} (it is a UUID) and neither can the secret (it is base64url), so splitting on the
     * first one after the prefix is unambiguous.
     */
    static Optional<TicketCredentials> offeredTicketCredentials(HttpHeaders headers) {
        List<String> header = headers.get("Sec-WebSocket-Protocol");
        if (header == null) return Optional.empty();
        return offers(header)
                .filter(offer -> offer.startsWith(TICKET_PROTOCOL_PREFIX) && offer.length() > TICKET_PROTOCOL_PREFIX.length())
                .map(offer -> offer.substring(TICKET_PROTOCOL_PREFIX.length()))
                .map(RealtimeEndpoint::split)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static Optional<TicketCredentials> split(String rest) {
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot >= rest.length() - 1) return Optional.empty();
        return Optional.of(new TicketCredentials(rest.substring(0, dot), rest.substring(dot + 1)));
    }

    private static java.util.stream.Stream<String> offers(List<String> headerLines) {
        return headerLines.stream().flatMap(line -> Arrays.stream(line.split(","))).map(String::strip);
    }
}
