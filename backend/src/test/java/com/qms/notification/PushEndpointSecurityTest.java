package com.qms.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** SSRF hardening on a visitor-supplied push endpoint (ticket 39): HTTPS required, and no loopback, link-local
 * (which also covers the 169.254.169.254 cloud metadata address), private or wildcard/multicast address. */
class PushEndpointSecurityTest {

    @Test
    void aRealPushServiceStyleHttpsHostOnAPublicAddressIsAccepted() {
        // 203.0.113.10 is RFC 5737's TEST-NET-3, a public documentation range: not loopback/private/link-local, and
        // being a literal address it needs no DNS lookup, so this stays hermetic in a sandboxed test run.
        assertThatCode(() -> PushEndpointSecurity.requireSafe("https://203.0.113.10/push/abc", false)).doesNotThrowAnyException();
    }

    @Test
    void plainHttpIsRejectedEvenOnAPublicAddress() {
        assertThatThrownBy(() -> PushEndpointSecurity.requireSafe("http://203.0.113.10/push/abc", false))
                .isInstanceOf(PushEndpointSecurity.UnsafeEndpointException.class)
                .hasMessage("scheme");
    }

    @Test
    void loopbackIsRejected() {
        assertThatThrownBy(() -> PushEndpointSecurity.requireSafe("https://127.0.0.1/push/abc", false))
                .isInstanceOf(PushEndpointSecurity.UnsafeEndpointException.class)
                .hasMessage("private_address");
    }

    @Test
    void theCloudMetadataAddressIsRejectedAsLinkLocal() {
        assertThatThrownBy(() -> PushEndpointSecurity.requireSafe("https://169.254.169.254/latest/meta-data", false))
                .isInstanceOf(PushEndpointSecurity.UnsafeEndpointException.class)
                .hasMessage("private_address");
    }

    @Test
    void aPrivateRfc1918AddressIsRejected() {
        assertThatThrownBy(() -> PushEndpointSecurity.requireSafe("https://10.0.0.5/admin", false))
                .isInstanceOf(PushEndpointSecurity.UnsafeEndpointException.class)
                .hasMessage("private_address");
        assertThatThrownBy(() -> PushEndpointSecurity.requireSafe("https://192.168.1.1/admin", false))
                .isInstanceOf(PushEndpointSecurity.UnsafeEndpointException.class)
                .hasMessage("private_address");
    }

    @Test
    void aMalformedUrlOrAMissingHostIsRejected() {
        assertThatThrownBy(() -> PushEndpointSecurity.requireSafe("not a url", false))
                .isInstanceOf(PushEndpointSecurity.UnsafeEndpointException.class);
        assertThatThrownBy(() -> PushEndpointSecurity.requireSafe("https:///no-host", false))
                .isInstanceOf(PushEndpointSecurity.UnsafeEndpointException.class)
                .hasMessage("host");
    }

    @Test
    void theTestOnlyBypassSkipsEveryCheck() {
        assertThatCode(() -> PushEndpointSecurity.requireSafe("http://127.0.0.1:9999/push/abc", true)).doesNotThrowAnyException();
    }
}
