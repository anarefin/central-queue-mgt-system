package com.qms.platform.realtime;

import com.qms.platform.Profiles;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * Registers {@code /api/v1/stream} (SRS §21.1). The security filter chain has already authenticated the upgrade request
 * from the same bearer token as REST, taken from the {@code Authorization} header or, for a browser, the subprotocol
 * (see {@link RealtimeEndpoint}); the interceptor hands that authentication to the connection so each topic can be
 * authorised as its owner (FR-QUE-080). Browsers may only connect from the page's own origin.
 *
 * <p>An anonymous visitor (§20.2, FR-SEC-033, ticket 37) carries no JWT, so the filter chain leaves the request anonymous;
 * {@code SecurityConfig} lets the upgrade itself through for this one path, and the interceptor here checks a ticket id
 * and secret offered the same way a token is, building the connection's authentication itself when they verify.
 */
@Configuration
@EnableWebSocket
@Profile(Profiles.SERVING)
class RealtimeConfig implements WebSocketConfigurer {

    private final RealtimeHub hub;
    private final Optional<TicketPrincipalVerifier> ticketVerifier;

    RealtimeConfig(RealtimeHub hub, Optional<TicketPrincipalVerifier> ticketVerifier) {
        this.hub = hub;
        this.ticketVerifier = ticketVerifier;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        DefaultHandshakeHandler handshake = new DefaultHandshakeHandler();
        handshake.setSupportedProtocols(RealtimeEndpoint.PROTOCOL);
        registry.addHandler(new StreamHandler(hub), RealtimeEndpoint.PATH).setHandshakeHandler(handshake).addInterceptors(new Authenticated(ticketVerifier));
    }

    /** Refuses an upgrade neither the filter chain nor a ticket secret authenticated, so the handler never sees an anonymous connection. */
    static final class Authenticated implements HandshakeInterceptor {

        private final Optional<TicketPrincipalVerifier> ticketVerifier;

        Authenticated(Optional<TicketPrincipalVerifier> ticketVerifier) {
            this.ticketVerifier = ticketVerifier;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
                attributes.put(StreamHandler.AUTHENTICATION, jwt);
                return true;
            }
            Optional<Authentication> ticketAuth = ticketVerifier
                    .flatMap(verifier -> RealtimeEndpoint.offeredTicketCredentials(request.getHeaders())
                            .flatMap(offered -> verifier.verify(offered.ticketId(), offered.secret())))
                    .map(Authentication.class::cast);
            if (ticketAuth.isPresent()) {
                attributes.put(StreamHandler.AUTHENTICATION, ticketAuth.get());
                return true;
            }
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception failure) {}
    }
}
