package com.qms.platform.realtime;

import com.qms.platform.ApiPathConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Optional;

/** Where the hub listens and how a browser presents its token to it (SRS §21.1). */
public final class RealtimeEndpoint {

    /** {@code wss://<host>/api/v1/stream}. */
    public static final String PATH = ApiPathConfig.BASE_PATH + "/stream";
    /** The one subprotocol the hub speaks. */
    public static final String PROTOCOL = "qms.v1";
    /** A browser cannot set {@code Authorization} on a WebSocket, so it offers the token as a second subprotocol. */
    static final String TOKEN_PROTOCOL_PREFIX = "bearer.";

    private RealtimeEndpoint() {}

    /**
     * The access token a browser offered as {@code Sec-WebSocket-Protocol: qms.v1, bearer.<token>}, on a connection to the
     * hub only. Anything else is left to the standard {@code Authorization} header, which non-browser clients use.
     */
    public static Optional<String> offeredToken(HttpServletRequest request) {
        if (!PATH.equals(request.getRequestURI())) return Optional.empty();
        String header = request.getHeader("Sec-WebSocket-Protocol");
        if (header == null) return Optional.empty();
        return Arrays.stream(header.split(","))
                .map(String::strip)
                .filter(offer -> offer.startsWith(TOKEN_PROTOCOL_PREFIX) && offer.length() > TOKEN_PROTOCOL_PREFIX.length())
                .map(offer -> offer.substring(TOKEN_PROTOCOL_PREFIX.length()))
                .findFirst();
    }
}
