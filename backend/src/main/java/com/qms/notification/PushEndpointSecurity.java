package com.qms.notification;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * Guards a push endpoint against server-side request forgery (ticket 39): a visitor's own {@code push-subscription}
 * body names a URL this server later POSTs to, unauthenticated, on its own initiative, from a background job — the
 * exact shape of an SSRF vector if the URL is trusted unchecked (an internal service, or a cloud metadata endpoint).
 * Requires HTTPS (RFC 8292 assumes it regardless of this check) and resolves the host, rejecting any address that is
 * loopback, link-local (this also catches the 169.254.169.254 cloud metadata address), site-local (the RFC 1918 /
 * unique-local private ranges), a wildcard or multicast address. Called both when a visitor subscribes and again
 * immediately before every send ({@link WebPushChannel}), since DNS can rebind between the two.
 */
final class PushEndpointSecurity {

    private PushEndpointSecurity() {}

    static final class UnsafeEndpointException extends RuntimeException {
        UnsafeEndpointException(String reason) {
            super(reason);
        }
    }

    /** {@code allowInsecureForTests} ({@link WebPushProperties#allowInsecureEndpointsForTests()}) exists only for a
     * test's own local fake push service; no environment variable wires it, and it defaults to {@code false}. */
    static void requireSafe(String endpoint, boolean allowInsecureForTests) {
        if (allowInsecureForTests) return;
        URI uri = parse(endpoint);
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new UnsafeEndpointException("scheme");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new UnsafeEndpointException("host");
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new UnsafeEndpointException("unresolvable");
        }
        if (addresses.length == 0) throw new UnsafeEndpointException("unresolvable");
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                throw new UnsafeEndpointException("private_address");
            }
        }
    }

    private static URI parse(String endpoint) {
        try {
            return new URI(endpoint);
        } catch (URISyntaxException | NullPointerException e) {
            throw new UnsafeEndpointException("malformed");
        }
    }
}
