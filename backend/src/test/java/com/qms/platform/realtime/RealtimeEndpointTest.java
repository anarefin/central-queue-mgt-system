package com.qms.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** SRS §21.1: a browser cannot set {@code Authorization} on a WebSocket, so it offers the token as a subprotocol, to the hub only. */
class RealtimeEndpointTest {

    private static MockHttpServletRequest request(String uri, String protocols) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (protocols != null) request.addHeader("Sec-WebSocket-Protocol", protocols);
        return request;
    }

    @Test
    void theTokenIsTheBearerOfferAlongsideTheProtocolTheHubSpeaks() {
        assertThat(RealtimeEndpoint.offeredToken(request(RealtimeEndpoint.PATH, "qms.v1, bearer.aaa.bbb.ccc"))).contains("aaa.bbb.ccc");
        assertThat(RealtimeEndpoint.offeredToken(request(RealtimeEndpoint.PATH, "bearer.aaa.bbb.ccc,qms.v1"))).contains("aaa.bbb.ccc");
    }

    @Test
    void noOfferOrAnEmptyOneIsNoToken() {
        assertThat(RealtimeEndpoint.offeredToken(request(RealtimeEndpoint.PATH, null))).isEmpty();
        assertThat(RealtimeEndpoint.offeredToken(request(RealtimeEndpoint.PATH, "qms.v1"))).isEmpty();
        assertThat(RealtimeEndpoint.offeredToken(request(RealtimeEndpoint.PATH, "qms.v1, bearer."))).isEmpty();
    }

    @Test
    void otherEndpointsNeverTakeTheirTokenFromTheSubprotocol() {
        assertThat(RealtimeEndpoint.offeredToken(request("/api/v1/users", "qms.v1, bearer.aaa.bbb.ccc"))).isEmpty();
        assertThat(RealtimeEndpoint.offeredToken(request(RealtimeEndpoint.PATH + "/snapshot", "bearer.aaa.bbb.ccc"))).isEmpty();
    }
}
