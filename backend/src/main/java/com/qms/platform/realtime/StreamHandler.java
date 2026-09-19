package com.qms.platform.realtime;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Carries the hub's frames over a WebSocket. The protocol itself lives in {@link RealtimeHub}. */
final class StreamHandler extends TextWebSocketHandler {

    static final String AUTHENTICATION = "qms.authentication";
    private static final String CONNECTION = "qms.connection";
    private static final Logger log = LoggerFactory.getLogger(StreamHandler.class);
    /** A client that cannot take a frame within this long, or lets this much pile up, is cut off rather than slowing the topic. */
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_LIMIT_BYTES = 512 * 1024;

    private final RealtimeHub hub;

    StreamHandler(RealtimeHub hub) {
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Authentication authentication = (Authentication) session.getAttributes().get(AUTHENTICATION);
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES);
        Connection connection = hub.open(new Outbound() {
            @Override
            public void send(String frame) throws IOException {
                safe.sendMessage(new TextMessage(frame));
            }

            @Override
            public void close(int code, String reason) {
                try {
                    safe.close(new CloseStatus(code, reason));
                } catch (IOException | RuntimeException alreadyGone) {
                    log.debug("closing a realtime socket failed: {}", alreadyGone.toString());
                }
            }
        }, authentication);
        session.getAttributes().put(CONNECTION, connection);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection connection = (Connection) session.getAttributes().get(CONNECTION);
        if (connection != null) hub.receive(connection, message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable failure) {
        log.debug("realtime transport error: {}", failure.toString());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Connection connection = (Connection) session.getAttributes().remove(CONNECTION);
        if (connection != null) hub.close(connection);
    }
}
