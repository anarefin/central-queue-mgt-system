package com.qms.platform.realtime;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** One client's connection to the hub: who it is, what it is subscribed to and when it last spoke. */
final class Connection {

    private static final Logger log = LoggerFactory.getLogger(Connection.class);
    static final int SEND_FAILED = 1011;

    private final Outbound out;
    private volatile Authentication authentication;
    private volatile ScheduledFuture<?> expiry;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private volatile Instant lastReceived;

    Connection(Outbound out, Authentication authentication, Instant now) {
        this.out = out;
        this.authentication = authentication;
        this.lastReceived = now;
    }

    Authentication authentication() {
        return authentication;
    }

    /** A {@code reauth} frame replaced the token this connection was opened with (ADR-0009). */
    void reauthenticate(Authentication fresh) {
        this.authentication = fresh;
    }

    /** The token's {@code sub}: who a {@code principal.changed} is about. */
    String subject() {
        return authentication.getName();
    }

    /** When the token expires, or null for an authentication that does not expire (only ever a test's). */
    Instant expiresAt() {
        return authentication instanceof JwtAuthenticationToken jwt ? jwt.getToken().getExpiresAt() : null;
    }

    static Instant issuedAt(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken jwt ? jwt.getToken().getIssuedAt() : null;
    }

    /** The timer that closes this connection at {@link #expiresAt}; replaced at each reauth. */
    void expiry(ScheduledFuture<?> timer) {
        ScheduledFuture<?> previous = this.expiry;
        this.expiry = timer;
        if (previous != null) previous.cancel(false);
    }

    Map<String, Subscription> subscriptions() {
        return subscriptions;
    }

    Instant lastReceived() {
        return lastReceived;
    }

    void heardAt(Instant now) {
        this.lastReceived = now;
    }

    /**
     * Sends a frame; a transport that cannot take it is closed, which ends this connection's subscriptions through the
     * normal close path. This never calls back into the hub, because it runs while a topic is locked.
     */
    void send(String frame) {
        try {
            out.send(frame);
        } catch (Exception failure) {
            log.debug("realtime send failed, closing the connection: {}", failure.toString());
            close(SEND_FAILED, "send failed");
        }
    }

    void close(int code, String reason) {
        try {
            out.close(code, reason);
        } catch (RuntimeException ignored) {
            // already closed
        }
    }
}
