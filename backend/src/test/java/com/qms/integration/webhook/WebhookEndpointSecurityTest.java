package com.qms.integration.webhook;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** SSRF hardening on an admin-supplied webhook endpoint URL: HTTPS required, and no loopback, link-local (which
 * also covers the 169.254.169.254 cloud metadata address), private or wildcard/multicast address. Trimmed from the
 * same shape {@code notification.PushEndpointSecurityTest} already covers for ticket 39's own endpoint field. */
class WebhookEndpointSecurityTest {

    @Test
    void aRealEndpointStyleHttpsHostOnAPublicAddressIsAccepted() {
        // 203.0.113.10 is RFC 5737's TEST-NET-3, a public documentation range: not loopback/private/link-local, and
        // being a literal address it needs no DNS lookup, so this stays hermetic in a sandboxed test run.
        assertThatCode(() -> WebhookEndpointSecurity.requireSafe("https://203.0.113.10/hooks/abc", false)).doesNotThrowAnyException();
    }

    @Test
    void plainHttpIsRejectedEvenOnAPublicAddress() {
        assertThatThrownBy(() -> WebhookEndpointSecurity.requireSafe("http://203.0.113.10/hooks/abc", false))
                .isInstanceOf(WebhookEndpointSecurity.UnsafeEndpointException.class)
                .hasMessage("scheme");
    }

    @Test
    void loopbackIsRejected() {
        assertThatThrownBy(() -> WebhookEndpointSecurity.requireSafe("https://127.0.0.1/hooks/abc", false))
                .isInstanceOf(WebhookEndpointSecurity.UnsafeEndpointException.class)
                .hasMessage("private_address");
    }

    @Test
    void theCloudMetadataAddressIsRejectedAsLinkLocal() {
        assertThatThrownBy(() -> WebhookEndpointSecurity.requireSafe("https://169.254.169.254/latest/meta-data", false))
                .isInstanceOf(WebhookEndpointSecurity.UnsafeEndpointException.class)
                .hasMessage("private_address");
    }

    @Test
    void aPrivateRfc1918AddressIsRejected() {
        assertThatThrownBy(() -> WebhookEndpointSecurity.requireSafe("https://10.0.0.5/admin", false))
                .isInstanceOf(WebhookEndpointSecurity.UnsafeEndpointException.class)
                .hasMessage("private_address");
        assertThatThrownBy(() -> WebhookEndpointSecurity.requireSafe("https://192.168.1.1/admin", false))
                .isInstanceOf(WebhookEndpointSecurity.UnsafeEndpointException.class)
                .hasMessage("private_address");
    }

    @Test
    void anIpv6UniqueLocalAddressIsRejected() {
        assertThatThrownBy(() -> WebhookEndpointSecurity.requireSafe("https://[fd00::1]/hooks/abc", false))
                .isInstanceOf(WebhookEndpointSecurity.UnsafeEndpointException.class)
                .hasMessage("private_address");
    }

    @Test
    void anIpv4MappedIpv6LoopbackLiteralIsRejected() {
        assertThatThrownBy(() -> WebhookEndpointSecurity.requireSafe("https://[::ffff:127.0.0.1]/hooks/abc", false))
                .isInstanceOf(WebhookEndpointSecurity.UnsafeEndpointException.class)
                .hasMessage("private_address");
    }

    @Test
    void aMalformedUrlOrAMissingHostIsRejected() {
        assertThatThrownBy(() -> WebhookEndpointSecurity.requireSafe("not a url", false))
                .isInstanceOf(WebhookEndpointSecurity.UnsafeEndpointException.class);
        assertThatThrownBy(() -> WebhookEndpointSecurity.requireSafe("https:///no-host", false))
                .isInstanceOf(WebhookEndpointSecurity.UnsafeEndpointException.class)
                .hasMessage("host");
    }

    @Test
    void theTestOnlyBypassSkipsEveryCheck() {
        assertThatCode(() -> WebhookEndpointSecurity.requireSafe("http://127.0.0.1:9999/hooks/abc", true)).doesNotThrowAnyException();
    }
}
