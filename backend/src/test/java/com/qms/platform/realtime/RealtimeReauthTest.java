package com.qms.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.realtime.RealtimeHubTest.FakeSource;
import com.qms.platform.realtime.RealtimeHubTest.Wire;
import com.qms.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * A socket cannot outlive its credentials (ADR-0009, SRS §21.1, FR-QUE-080), driven with frames and a clock the test moves:
 * the hub closes a socket at the token's {@code exp} unless a {@code reauth} frame with a fresh token arrived first, and
 * {@code principal.changed(sub)} drops that subject's sockets at once and stops an older token being used to come back.
 */
class RealtimeReauthTest {

    static final JsonMapper MAPPER = RealtimeHubTest.MAPPER;
    static final Instant T0 = RealtimeHubTest.T0;
    static final String QUEUE = RealtimeHubTest.QUEUE;
    static final String MAY_SEE = "may-see";
    static final Duration TTL = Duration.ofMinutes(15);

    /** Owns the queue topic and lets in only a subscriber whose token carries {@code may-see}. */
    static class ClaimsSource extends FakeSource {
        @Override
        public void authorize(String topic) {
            boolean allowed = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a -> MAY_SEE.equals(a.getAuthority()));
            if (!allowed) throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    /** Stands in for the resource server's decoder: knows the tokens the test registered and rejects any other. */
    static class FakeVerifier implements TokenVerifier {
        final Map<String, Authentication> known = new HashMap<>();

        public Authentication verify(String token) {
            Authentication found = known.get(token);
            if (found == null) throw new BadCredentialsException("not a token");
            return found;
        }
    }

    MutableClock clock = new MutableClock(T0);
    FakeVerifier verifier = new FakeVerifier();
    ClaimsSource source = new ClaimsSource();
    RealtimeHub hub;
    final List<Connection> connections = new ArrayList<>();

    @BeforeEach
    void newHub() {
        hub = new RealtimeHub(List.of(source), RealtimeProperties.defaults(), MAPPER, clock, Optional.of(verifier), event -> {});
    }

    @AfterEach
    void stop() {
        hub.close();
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clear();
    }

    /** A token for {@code sub} issued at {@code issuedAt} and good for fifteen minutes, registered with the verifier under its own value. */
    private JwtAuthenticationToken token(String sub, Instant issuedAt, String... authorities) {
        Jwt jwt = Jwt.withTokenValue(sub + "@" + issuedAt).header("alg", "ES256").subject(sub).issuedAt(issuedAt).expiresAt(issuedAt.plus(TTL)).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        verifier.known.put(jwt.getTokenValue(), authentication);
        return authentication;
    }

    private Wire connect(Authentication as) {
        Wire wire = new Wire();
        connections.add(hub.open(wire, as));
        return wire;
    }

    private Connection last() {
        return connections.getLast();
    }

    private void say(Connection connection, Map<String, Object> frame) {
        hub.receive(connection, MAPPER.writeValueAsString(frame));
    }

    private void subscribe(Connection connection, String topic) {
        say(connection, Map.of("frame", "subscribe", "topics", List.of(topic)));
    }

    private void reauth(Connection connection, Authentication with) {
        say(connection, Map.of("frame", "reauth", "token", ((JwtAuthenticationToken) with).getToken().getTokenValue()));
    }

    /** Time passes with the client still talking (a heartbeat), and the hub takes its turn. */
    private void pass(Connection connection, Duration time) {
        clock.advance(time);
        say(connection, Map.of("frame", "heartbeat"));
        hub.beat();
    }

    private void publish(int n) {
        hub.publish(QUEUE, "ticket.called", clock.instant(), Map.of("n", n));
    }

    // ---- ADR-0009, §21.1: a socket closes at token expiry unless it re-authenticates first ---------------------------

    @Test
    void aSocketIsClosedWhenItsTokenExpiresIfNoReauthArrived() {
        Wire wire = connect(token("agent-1", T0, MAY_SEE));
        subscribe(last(), QUEUE);

        pass(last(), TTL.minusSeconds(1));
        assertThat(wire.closes).as("still inside its token's life").isEmpty();

        pass(last(), Duration.ofSeconds(1));
        assertThat(wire.closes).containsExactly(RealtimeHub.TOKEN_EXPIRED);
        publish(1);
        assertThat(wire.frames("event")).as("its subscriptions ended with it").isEmpty();
        assertThat(hub.connectionCount()).isZero();
    }

    @Test
    void aReauthFrameWithAFreshTokenKeepsTheSocketPastTheOldExpiryAndItThenLivesToTheNewOne() {
        Wire wire = connect(token("agent-1", T0, MAY_SEE));
        subscribe(last(), QUEUE);
        pass(last(), Duration.ofMinutes(14));
        Instant issued = clock.instant();
        reauth(last(), token("agent-1", issued, MAY_SEE));

        assertThat(wire.last()).containsEntry("frame", "reauth").containsEntry("expires_at", issued.plus(TTL).toString());

        pass(last(), Duration.ofMinutes(2));
        assertThat(wire.closes).as("past the first token's expiry").isEmpty();
        publish(1);
        assertThat(wire.frames("event")).as("still subscribed").hasSize(1);

        pass(last(), Duration.ofMinutes(12));
        assertThat(wire.closes).as("the fresh token has not expired yet").isEmpty();
        pass(last(), Duration.ofMinutes(1));
        assertThat(wire.closes).containsExactly(RealtimeHub.TOKEN_EXPIRED);
    }

    @Test
    void aReauthThatIsRefusedLeavesTheSocketToCloseAtItsOldExpiryAndTellsTheClient() {
        Wire wire = connect(token("agent-1", T0, MAY_SEE));
        pass(last(), Duration.ofMinutes(10));

        say(last(), Map.of("frame", "reauth", "token", "garbage"));
        assertThat(wire.last()).containsEntry("frame", "error").containsEntry("code", "unauthorized");

        say(last(), Map.of("frame", "reauth", "token", token("someone-else", clock.instant(), MAY_SEE).getToken().getTokenValue()));
        assertThat(wire.last()).as("another subject's token").containsEntry("frame", "error").containsEntry("code", "unauthorized");

        say(last(), Map.of("frame", "reauth"));
        assertThat(wire.last()).as("no token at all").containsEntry("code", "unauthorized");

        pass(last(), Duration.ofMinutes(5));
        assertThat(wire.closes).containsExactly(RealtimeHub.TOKEN_EXPIRED);
    }

    @Test
    void aReauthWithATokenThatHasAlreadyExpiredIsRefused() {
        JwtAuthenticationToken stale = token("agent-1", T0, MAY_SEE);
        Wire wire = connect(token("agent-1", T0.plusSeconds(1), MAY_SEE));
        clock.advance(TTL.plusSeconds(1).minusMillis(1));
        say(last(), Map.of("frame", "heartbeat"));

        reauth(last(), stale);

        assertThat(wire.last()).containsEntry("frame", "error").containsEntry("code", "unauthorized");
    }

    @Test
    void aReauthReauthorisesTheSubscriptionsAgainstTheNewClaims() {
        Wire wire = connect(token("agent-1", T0, MAY_SEE));
        subscribe(last(), QUEUE);
        publish(1);
        assertThat(wire.frames("event")).hasSize(1);
        pass(last(), Duration.ofMinutes(5));

        reauth(last(), token("agent-1", clock.instant())); // the new token no longer carries the permission

        assertThat(wire.frames("denied")).singleElement().satisfies(f -> assertThat(f).containsEntry("topic", QUEUE).containsEntry("code", "forbidden"));
        publish(2);
        assertThat(wire.frames("event")).as("nothing more after the permission went").hasSize(1);
        assertThat(wire.frames("reauth")).hasSize(1);
    }

    // ---- ADR-0009, FR-QUE-080: principal.changed drops that subject's sockets at once --------------------------------

    @Test
    void principalChangedClosesEverySocketOfThatSubjectAtOnceAndNoOtherAndEndsTheirSubscriptions() {
        Wire first = connect(token("agent-1", T0, MAY_SEE));
        subscribe(last(), QUEUE);
        Wire second = connect(token("agent-1", T0, MAY_SEE));
        subscribe(last(), QUEUE);
        Wire other = connect(token("agent-2", T0, MAY_SEE));
        subscribe(last(), QUEUE);
        clock.advance(Duration.ofSeconds(30));

        hub.principalChanged("agent-1");

        assertThat(first.closes).containsExactly(RealtimeHub.PRINCIPAL_CHANGED);
        assertThat(second.closes).containsExactly(RealtimeHub.PRINCIPAL_CHANGED);
        assertThat(other.closes).isEmpty();
        assertThat(hub.connectionCount()).isEqualTo(1);
        publish(1);
        assertThat(first.frames("event")).isEmpty();
        assertThat(second.frames("event")).isEmpty();
        assertThat(other.frames("event")).hasSize(1);
    }

    @Test
    void principalChangedInATransactionActsOnlyOnceItCommitsAndNeverWhenItRollsBack() {
        Wire wire = connect(token("agent-1", T0, MAY_SEE));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            hub.principalChanged("agent-1");
            assertThat(wire.closes).isEmpty();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationUtils.triggerAfterCommit();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clear();
        }
        assertThat(wire.closes).containsExactly(RealtimeHub.PRINCIPAL_CHANGED);

        Wire safe = connect(token("agent-2", T0, MAY_SEE));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            hub.principalChanged("agent-2");
            TransactionSynchronizationUtils.invokeAfterCompletion(TransactionSynchronizationManager.getSynchronizations(), TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clear();
        }
        assertThat(safe.closes).as("a rolled-back disable drops nobody").isEmpty();
    }

    @Test
    void afterAPrincipalChangeATokenIssuedBeforeItCannotComeBackNorReauthButAFreshOneCan() {
        JwtAuthenticationToken before = token("agent-1", T0, MAY_SEE);
        connect(before);
        clock.advance(Duration.ofSeconds(10));
        Instant changedAt = clock.instant();
        hub.principalChanged("agent-1");

        Wire again = connect(before);
        assertThat(again.closes).as("the token it had cannot bring it back").containsExactly(RealtimeHub.PRINCIPAL_CHANGED);
        assertThat(hub.connectionCount()).isZero();

        Wire back = connect(token("agent-1", changedAt.plusSeconds(1), MAY_SEE));
        assertThat(back.closes).isEmpty();
        assertThat(hub.connectionCount()).isEqualTo(1);

        say(last(), Map.of("frame", "reauth", "token", before.getToken().getTokenValue()));
        assertThat(back.last()).as("the old token cannot extend a socket either").containsEntry("frame", "error").containsEntry("code", "unauthorized");
    }

    @Test
    void afterAPrincipalChangeTheReconnectingClientsTopicsAreAuthorisedAgainstItsNewClaims() {
        connect(token("agent-1", T0, MAY_SEE));
        subscribe(last(), QUEUE);
        clock.advance(Duration.ofSeconds(10));
        hub.principalChanged("agent-1");

        Wire back = connect(token("agent-1", clock.instant().plusSeconds(1))); // roles changed: the new token has no permission for the topic
        subscribe(last(), QUEUE);

        assertThat(back.frames("denied")).singleElement().satisfies(f -> assertThat(f).containsEntry("topic", QUEUE).containsEntry("code", "forbidden"));
        assertThat(back.frames("snapshot")).isEmpty();
    }

    @Test
    void theHubForgetsAPrincipalChangeOnceNoTokenIssuedBeforeItCanStillBeValid() {
        JwtAuthenticationToken before = token("agent-1", T0, MAY_SEE);
        clock.advance(Duration.ofSeconds(10));
        hub.principalChanged("agent-1");
        assertThat(connect(before).closes).as("remembered while such a token could still be valid").containsExactly(RealtimeHub.PRINCIPAL_CHANGED);

        clock.advance(Duration.ofMinutes(21));
        hub.beat();

        assertThat(connect(before).closes).as("forgotten: such a token is long expired anyway").doesNotContain(RealtimeHub.PRINCIPAL_CHANGED);
    }
}
