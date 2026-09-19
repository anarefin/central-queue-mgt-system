package com.qms.platform.realtime;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

/** One client's connection to the hub: who it is, what it is subscribed to and when it last spoke. */
final class Connection {

    private static final Logger log = LoggerFactory.getLogger(Connection.class);
    static final int SEND_FAILED = 1011;

    private final Outbound out;
    private final Authentication authentication;
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
